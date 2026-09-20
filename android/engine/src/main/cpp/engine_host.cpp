// Janggi AI - in-process host for the Fairy-Stockfish UCI engine (see engine_host.h).
//
// This file is compiled *with* exceptions enabled (std::thread may throw); the engine
// sources keep their upstream -fno-exceptions flag.
#include "engine_host.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <iostream>
#include <mutex>
#include <streambuf>
#include <string>
#include <thread>

// Fairy-Stockfish headers (unmodified upstream sources in ./fairy-stockfish)
#include "bitboard.h"
#include "endgame.h"
#include "evaluate.h"
#include "misc.h"
#include "piece.h"
#include "position.h"
#include "psqt.h"
#include "search.h"
#include "syzygy/tbprobe.h"
#include "thread.h"
#include "tt.h"
#include "uci.h"
#include "variant.h"
#include "xboard.h"

namespace janggi {

namespace {

// ---------------------------------------------------------------------------- input side
// std::cin replacement.  underflow() blocks until a command line is available.  When the
// host closes the stream, EOF is returned; UCI::loop treats EOF as "quit".
class InputBuffer : public std::streambuf {
public:
    void push(const std::string& line) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            lines_.push_back(line + "\n");
        }
        cv_.notify_one();
    }

    void close() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            closed_ = true;
        }
        cv_.notify_all();
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        lines_.clear();
        current_.clear();
        closed_ = false;
        setg(nullptr, nullptr, nullptr);
    }

protected:
    int_type underflow() override {
        std::unique_lock<std::mutex> lock(mutex_);
        cv_.wait(lock, [this] { return closed_ || !lines_.empty(); });
        if (lines_.empty())
            return traits_type::eof();
        current_ = std::move(lines_.front());
        lines_.pop_front();
        char* begin = &current_[0];
        setg(begin, begin, begin + current_.size());
        return traits_type::to_int_type(*begin);
    }

private:
    std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<std::string> lines_;
    std::string current_;
    bool closed_ = false;
};

// ---------------------------------------------------------------------------- output side
// std::cout replacement.  Characters are accumulated per thread-agnostic line buffer under a
// mutex (the engine already serialises whole lines with sync_cout, this is belt and braces)
// and complete lines are queued for the reader.
class OutputBuffer : public std::streambuf {
public:
    int pop(std::string& out, int timeout_ms, const std::atomic<bool>& engineAlive) {
        std::unique_lock<std::mutex> lock(mutex_);
        auto ready = [&] { return !lines_.empty() || !engineAlive.load(); };
        if (timeout_ms <= 0) {
            if (!ready()) return 0;
        } else if (!cv_.wait_for(lock, std::chrono::milliseconds(timeout_ms), ready)) {
            return 0;
        }
        if (!lines_.empty()) {
            out = std::move(lines_.front());
            lines_.pop_front();
            return 1;
        }
        return -1;  // engine gone and nothing left
    }

    void flushPartial() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!partial_.empty()) {
            lines_.push_back(partial_);
            partial_.clear();
        }
        cv_.notify_all();
    }

    void wake() { cv_.notify_all(); }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        lines_.clear();
        partial_.clear();
    }

protected:
    int_type overflow(int_type ch) override {
        if (ch == traits_type::eof()) return traits_type::not_eof(ch);
        std::lock_guard<std::mutex> lock(mutex_);
        put(static_cast<char>(ch));
        return ch;
    }

    std::streamsize xsputn(const char* s, std::streamsize n) override {
        std::lock_guard<std::mutex> lock(mutex_);
        for (std::streamsize i = 0; i < n; ++i) put(s[i]);
        return n;
    }

    int sync() override { return 0; }

private:
    void put(char c) {  // mutex_ held
        if (c == '\n') {
            lines_.push_back(partial_);
            partial_.clear();
            cv_.notify_all();
        } else if (c != '\r') {
            partial_.push_back(c);
        }
    }

    std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<std::string> lines_;
    std::string partial_;
};

std::once_flag g_coreInit;

// Everything main() does before UCI::loop() except thread creation.  Idempotency of these
// initialisers is not guaranteed by the engine, so they run exactly once per process.
void initEngineCoreOnce() {
    std::call_once(g_coreInit, [] {
        using namespace Stockfish;
        static char name[] = "janggi-engine";
        static char* argv[] = {name, nullptr};
        pieceMap.init();
        variants.init();
        CommandLine::init(1, argv);
        UCI::init(Options);
        Tune::init();
        PSQT::init(variants.find(Options["UCI_Variant"])->second);
        Bitboards::init();
        Position::init();
        Bitbases::init();
        Endgames::init();
    });
}

}  // namespace

struct EngineHost::Impl {
    std::mutex lifecycle;           // serialises start/shutdown
    std::thread thread;
    std::atomic<bool> running{false};
    std::atomic<bool> loopEnded{true};
    InputBuffer input;
    OutputBuffer output;
    std::streambuf* savedCin = nullptr;
    std::streambuf* savedCout = nullptr;

    void run() {
        using namespace Stockfish;
        initEngineCoreOnce();
        Threads.set(size_t(Options["Threads"]));
        Search::clear();  // after threads are up
        Eval::NNUE::init();

        static char name[] = "janggi-engine";
        static char* argv[] = {name, nullptr};
        UCI::loop(1, argv);  // returns after "quit" (or EOF on the input buffer)

        Threads.set(0);
        delete XBoard::stateMachine;
        XBoard::stateMachine = nullptr;

        output.flushPartial();
        running.store(false);
        loopEnded.store(true);
        output.wake();
    }
};

EngineHost::EngineHost() : impl_(new Impl()) {}
EngineHost::~EngineHost() { delete impl_; }

EngineHost& EngineHost::instance() {
    static EngineHost host;
    return host;
}

bool EngineHost::start() {
    std::lock_guard<std::mutex> lock(impl_->lifecycle);
    if (impl_->running.load()) return true;
    if (impl_->thread.joinable()) impl_->thread.join();  // previous loop fully ended

    impl_->input.reset();
    impl_->output.reset();
    impl_->savedCin = std::cin.rdbuf(&impl_->input);
    impl_->savedCout = std::cout.rdbuf(&impl_->output);
    impl_->running.store(true);
    impl_->loopEnded.store(false);
    try {
        impl_->thread = std::thread([this] { impl_->run(); });
    } catch (...) {
        std::cin.rdbuf(impl_->savedCin);
        std::cout.rdbuf(impl_->savedCout);
        impl_->running.store(false);
        impl_->loopEnded.store(true);
        return false;
    }
    return true;
}

void EngineHost::send(const std::string& line) {
    if (!impl_->running.load()) return;
    // "stop"/"quit" must not wait behind a blocked search: UCI::loop reads the next command
    // while the search runs on worker threads, so queueing is sufficient.  We additionally
    // raise the engine's stop flag directly so a long search reacts even if the loop thread
    // is momentarily busy processing a previous command.
    if (line == "stop" || line == "quit") Stockfish::Threads.stop = true;
    impl_->input.push(line);
}

int EngineHost::readLine(std::string& out, int timeout_ms) {
    return impl_->output.pop(out, timeout_ms, impl_->running);
}

bool EngineHost::shutdown(int timeout_ms) {
    std::lock_guard<std::mutex> lock(impl_->lifecycle);
    if (!impl_->thread.joinable()) {
        impl_->running.store(false);
        return true;
    }
    if (impl_->running.load()) {
        Stockfish::Threads.stop = true;
        impl_->input.push("quit");
        impl_->input.close();
    }
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    while (!impl_->loopEnded.load() && std::chrono::steady_clock::now() < deadline)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    bool ended = impl_->loopEnded.load();
    if (ended) {
        impl_->thread.join();
        if (impl_->savedCin) std::cin.rdbuf(impl_->savedCin);
        if (impl_->savedCout) std::cout.rdbuf(impl_->savedCout);
        impl_->savedCin = impl_->savedCout = nullptr;
    }
    impl_->running.store(false);
    return ended;
}

bool EngineHost::isRunning() const { return impl_->running.load(); }

std::string EngineHost::engineInfo() const { return Stockfish::engine_info(true); }

}  // namespace janggi
