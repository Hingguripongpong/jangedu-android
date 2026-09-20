// Janggi AI - in-process host for the Fairy-Stockfish UCI engine.
//
// Fairy-Stockfish is a command-line program: it reads UCI commands from std::cin and
// writes answers to std::cout.  Instead of forking a process (which Android restricts
// and which ties the engine's lifetime to a shell process we cannot fully control), the
// host runs the *unmodified* engine loop on a dedicated thread with both standard
// streams redirected to in-memory buffers:
//
//     Kotlin  --send(line)-->  InputQueue  --std::cin-->  UCI::loop()  (engine thread)
//     Kotlin  <--readLine()--  OutputQueue <--std::cout-- sync_cout    (search threads)
//
// The text protocol is kept on purpose: it is the engine's stable, well-tested interface,
// the Kotlin side already speaks it, and no line of Fairy-Stockfish source is patched.
// Only main() is left out (it is replaced by EngineHost::start()).
//
// Thread-safety: all public functions may be called from any thread.  Engine-core
// initialisation (piece/variant tables, bitboards, options) happens once per process;
// the UCI loop and the search thread pool can be started and shut down repeatedly.
#ifndef JANGGI_ENGINE_HOST_H
#define JANGGI_ENGINE_HOST_H

#include <string>

namespace janggi {

class EngineHost {
public:
    static EngineHost& instance();

    // Starts the engine thread (no-op if already running).  Returns false if the
    // thread could not be created.  Safe to call again after shutdown().
    bool start();

    // Queues one UCI command line (without the trailing newline).
    void send(const std::string& line);

    // Waits up to timeout_ms for one output line.  Returns:
    //   1  a line was stored in `out`
    //   0  timeout, engine still alive
    //  -1  engine not running and no buffered output is left
    int readLine(std::string& out, int timeout_ms);

    // Sends "quit" and joins the engine thread.  Returns true if the thread ended
    // within timeout_ms (the engine is always marked not-running afterwards).
    bool shutdown(int timeout_ms);

    bool isRunning() const;

    // Human readable engine identification ("Fairy-Stockfish 14 LB ...").
    std::string engineInfo() const;

private:
    EngineHost();
    ~EngineHost();
    EngineHost(const EngineHost&) = delete;
    EngineHost& operator=(const EngineHost&) = delete;

    struct Impl;
    Impl* impl_;
};

}  // namespace janggi

#endif  // JANGGI_ENGINE_HOST_H
