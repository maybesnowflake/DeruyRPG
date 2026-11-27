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
| 문 (협곡 연결) | **입구 = 북쪽(z=0) 가장자리, `[spawn]` 의 x·높이** / **출구 = 남쪽 가장자리 `[exit]` 표지판** (기본: 가로 가운데·스폰 높이, `r.exit = (x, y)` 로 지정). `save()` 가 폭 3·높이 4 통로를 자동으로 뚫음. 캠프는 입구 없음, 보스방은 출구 없음. 스폰이 남쪽인 방은 `r.mirror_z()` |

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

**현재 (2026-11-27, rooms/jungle): 목표 수 전부 채움 — 30개.** camp 1 · combat 12 (open/water/vertical/narrow × 3) · elite 4 (태그별 1) · puzzle 4 · shop 3 · rest 2 · event 2 · miniboss 1 · boss 1.

| 풀 | 방 |
|---|---|
| combat open | jungle_clearing_open_01 · ruin_plaza_open_02 (가운데 단 + 기둥) · bamboo_meadow_open_03 (대나무 숲 + 언덕) |
| combat water | flooded_channel_water_01 · mangrove_delta_water_02 (수로 2줄, 서쪽 다리 우회) · sunken_pool_water_03 (깊이 2 연못 + 징검돌) |
| combat vertical | canopy_bridges_vertical_01 · temple_steps_vertical_02 (3단 피라미드, 정면 계단/옆 덩굴) · canopy_ladders_vertical_03 (6·12층 발판) |
| combat narrow | ruined_corridor_narrow_01 · root_tunnel_narrow_02 (S자 뿌리 굴) · collapsed_gallery_narrow_03 (쌍회랑 + 벽 틈 3) |
| elite | overgrown_arena_01 (open) · drowned_cistern_elite_01 (water) · canopy_throne_elite_01 (vertical) · guard_hall_elite_01 (narrow) |
| puzzle | vine_knot_01 (매듭 3) · water_gauge_01 (수위 기둥 = 정답 레버) · trap_corridor_01 (압력판·발사기·마그마) · moss_glyph_lever_01 (벽화 이끼 = 정답 레버) |
| shop / rest / event | wandering_trader_hut_01 · mossy_stall_shop_02 · temple_merchant_shop_03 / quiet_spring_01 · campfire_glade_rest_02 / trapped_explorer_01 · vine_shrine_event_02 (제단 레버 = [trigger]) |

### 도구 (tools/rooms)
- `jungle_kit.py` — 공용 부품: 잔해·기둥·통나무·대나무 숲·언덕·물길·계단·사다리·덩굴, `lever_lock`(레버 조합 자물쇠 → [trigger]), `lever_on_trigger`, `sign`(안내 표지판), `carve`
- `roomgen.py` 검증(save strict) 추가: 마커 발밑 단단함 + 2×2×2 빈칸, 물이 방 밖/허공과 닿으면 실패. 레드스톤 가루 모양 자동 맞춤(`shape_redstone`) — **배치가 물리 업데이트 없이 되므로**(RoomPlacer `update(true,false)`) 가루 모양·신호 세기·횃불 켜짐을 저장 단계에서 안정 상태로 맞춰 둠
- `check_circuits.py` — 퍼즐 회로 오프라인 검사: 초기 상태가 안정인지 + 레버 조합 전부 중 정답 1개만 램프를 켜는지
- 방마다 이름으로 시드를 가름 → 방을 추가해도 기존 방 모양 그대로

### 인게임 확인 필요
- 레버 자물쇠 실제 동작 (시뮬레이터는 같은 높이 단순 회로만 봄), 함정 발사기 화살이 압력판 위 플레이어를 맞추는지
- 사다리·덩굴 오르내림, 발판 울타리 틈, 대나무 블록 상태
- `/dg rooms` 에 30개 표시, 방 배치 렉

## 디자인 기준
- docs/JUNGLE_MAP.md "특기 무기 지형 보너스": 태그 방마다 특기 무기가 빛나는 지형 + 없어도 통과 가능한 대안
- 게임 디자인 원칙: 외형으로 규칙 알려주기(위험 바닥은 색으로), 피할 곳이 보이는 배치
- 바닐라 블록만, 마크틱 정글(정글 원목·이끼·맹그로브·덩굴·이끼 낀 석재)

## 서버 적용
`out/rooms/*` → `plugins/DeruyRPG/rooms/` 복사 → `/dg reload` → `/dg rooms` → `/dg setreturn` → `/dg start jungle`
