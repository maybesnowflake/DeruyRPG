# 맵 미리보기 (인게임 없이 확인)
1. 로컬 Paper 26.2 + JDK 25 (`/tmp/srv`, `/tmp/jdk25`), 플레이어 없이 시작되게 `start()` 의 `참가자 없음` 체크를 지운 테스트 빌드를 plugins/ 에
2. `run.sh` — 서버 켜기 → `/dg start jungle` → 저장 → 종료
3. `render.py <region폴더> out.png 1` — 위에서 본 지도
4. `fp.py <region폴더> out.png <x> <눈높이y> <z> <yaw> <pitch> X0 X1 Z0 Z1 Y0 Y1` — 플레이어 시점 레이트레이싱 (카메라는 실제 길 바닥 칸 위에)
5. `rooms_sheet.py <rooms폴더> out.png` — 방 .nbt 들 위에서 본 시트
