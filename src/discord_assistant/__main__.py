from .bot import main
from .logging_config import setup_logging

# 구조화 로깅 초기화 (#45). LOG_FORMAT=json|text (기본 text)에 따라
# 운영용 JSON 또는 사람용 텍스트 포맷을 선택한다. setup_logging은 멱등하다.
setup_logging()

if __name__ == "__main__":
    main()
