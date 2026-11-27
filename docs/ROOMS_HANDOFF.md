# 던전 방 제작 인수인계 (다른 세션용)

## 바로 쓰는 프롬프트 (새 세션에 붙여넣기)
```
DeruyRPG 저장소(maybesnowflake/DeruyRPG) feature/dungeon 브랜치에서 정글 챕터 던전 방 템플릿을 만들어줘.
1. docs/ROOMS_HANDOFF.md, docs/DUNGEON_GEN.md "결정" 섹션, docs/JUNGLE_MAP.md, docs/BOSS.md(보스방 조건)를 먼저 읽어.
2. tools/rooms/roomgen.py 의 Room 으로 방을 만들고, tools/rooms/jungle_rooms.py 처럼 생성 스크립트를 늘려.
   아래 "목표 수"를 채우고, 방마다 roomgen 검증(save strict)을 통과시켜.
3. python tools/rooms/jungle_rooms.py out/rooms 로 생성 → check() 출력으로 크기·마커 확인.
4. 브랜치 rooms/jungle 에 스크립트만 커밋(out/ 는 커밋 X) → feature/dungeon 대상 PR, 생성된 .nbt 는 zip 으로 내게 전달.
5. Codex 리뷰 요청(@codex review): 방 규격·마커·전투 동선 위주.
```

## 규격 (플러그인과의 계약)
| 항목 | 값 |
|---|---|
| 크기 | 가로·세로 ≤ 64, 높이 ≤ 250. 바닥 포함 (빈 월드에 배치) |
| 마커 표지판 | 첫 줄 `[spawn]` 정확히 1 · `[mob]` 몹 소환 지점 · `[boss]` 보스 소환 · `[trigger]` 퍼즐 완료(레드스톤 램프로 바뀜, 회로 출력 연결) |
| 마커 위치 | 바닥 바로 위 칸 (`Room.marker(x, 1, z, ...)`), 주변 2×2×2 이상 비어 있게 |
| 파일 | `rooms/<풀>/<이름>.nbt` + 선택 `<이름>.yml` (`tags`, `weight`) |
| 풀 | camp · combat · elite · puzzle · shop · rest · event · miniboss · boss |
| 태그 | vertical(수관길: 철퇴·활·쇠뇌) · water(수로: 삼지창·낚싯대) · narrow(회랑: 창·방패·검) · open(공터: 도끼·검) |
| 형식 | roomgen 이 DataVersion 4189(1.21.4)로 저장 → 서버가 자동 변환 |

## 목표 수 (정글 챕터)
| 풀 | 수 | 메모 |
|---|---|---|
| camp | 1 | 완료 (explorer_camp) |
| combat | 12+ | 태그 4종 × 3 이상. 엄폐물·고저차·우회로가 방마다 다르게 |
| elite | 4 | 태그 골고루 |
| puzzle | 4 | 덩굴 매듭·수위·함정 복도·레버 문양 (JUNGLE_MAP.md 퍼즐 표), `[trigger]` 필수 |
| shop | 3 | 상인 자리 (거래 창은 추후 플러그인) |
| rest | 2 | |
| event | 2 | "덩굴에 갇힌 탐험가" 등 |
| miniboss | 1 | 완료 (moss_gate_small_01) |
| boss | 1 | 완료 (sunken_floodgate_01) — 반경 ≥16 평지, 천장 ≥12, 물 링, 기둥 4 |

현재 샘플(jungle_rooms.py): camp 1, combat 4(태그별 1), elite 1, puzzle 1, shop 1, rest 1, event 1, miniboss 1, boss 1.

## 디자인 기준
- docs/JUNGLE_MAP.md "특기 무기 지형 보너스": 태그 방마다 특기 무기가 빛나는 지형 + 없어도 통과 가능한 대안
- 게임 디자인 원칙: 외형으로 규칙 알려주기(위험 바닥은 색으로), 피할 곳이 보이는 배치
- 바닐라 블록만, 마크틱 정글(정글 원목·이끼·맹그로브·덩굴·이끼 낀 석재)

## 서버 적용
`out/rooms/*` → `plugins/DeruyRPG/rooms/` 복사 → `/dg reload` → `/dg rooms` → `/dg setreturn` → `/dg start jungle`
