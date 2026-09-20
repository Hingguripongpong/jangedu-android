# engines/

Fairy-Stockfish 실행 파일을 이 폴더에 넣으면 UI의 "엔진" 선택에 나타납니다.

    python scripts/get_fairy_stockfish.py            # OS에 맞는 largeboard 빌드 자동 다운로드
    python scripts/get_fairy_stockfish.py --build modern   # 최신 CPU용(더 빠름), 오래된 CPU에서는 실행 불가

수동 다운로드: https://github.com/fairy-stockfish/Fairy-Stockfish/releases
장기(9x10)에는 `fairy-stockfish-largeboard_*` 빌드가 필요합니다. 다른 위치에 두었다면 환경변수
`JANGGI_FSF_PATH`로 경로를 지정하세요.
