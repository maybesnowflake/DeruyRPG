# DeruyRPG 작업 규칙

Paper 26.2 / Java 25 / Maven. 바닐라 무기 기반 RPG 스킬 플러그인.

## 구조
- `api/` — `WeaponKit`(무기 인터페이스), `SkillContext`
- `core/` — 입력 감지, 쿨다운, 피버 게이지, 마나, HUD (**무기 세션은 수정 금지**)
- `util/` — `Damage`(스킬 피해는 반드시 여기로), `Targets`(범위·아군 판정), `Fx`(파티클·사운드), `Items`
- `weapons/<무기>/` — 무기별 구현. **무기 세션은 자기 폴더만 수정**

## 무기 세션 규칙
1. 브랜치 `weapon/<id>` 에서 작업 (예: `weapon/sword`). main에 직접 푸시 금지.
2. `weapons/<id>/` 밖 파일은 수정하지 않는다. 코어에 기능이 필요하면 PR 설명에 "코어 요청"으로 적는다.
3. 스킬 명세는 `docs/SKILLS.md`. 수치는 근사값이라 조정 가능.
4. 스킬 피해 → `Damage.skill / pierce / magic`, 대상 → `Targets`, 쿨다운 → `ctx.tryCooldown(Slot.X, 틱)`.
5. 추가 이벤트가 필요하면 Kit 클래스가 `Listener`를 구현하면 자동 등록된다.
6. 소환물(병사·연출 엔티티)은 `Targets.markSummon()` 표시 후 반드시 제거(타이머 + `onUnequip`).
7. 연출은 바닐라 파티클·사운드·엔티티만.
8. 끝나면 `JAVA_HOME=<jdk25> mvn -q package` 통과 확인 후 푸시 → PR.

## 테스트 명령어
`/rpg give <무기|all>`, `/rpg fever` (게이지 가득), `/rpg mana`, `/rpg reset` (쿨다운 초기화)
