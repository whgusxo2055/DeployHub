-- image_tag를 대소문자 구분해 저장한다.
-- 테이블 기본 대조는 utf8mb4_0900_ai_ci(대소문자·악센트 무시)라 DB가 'acme/x:V1'과 'acme/x:v1'을
-- 같은 값으로 본다. 두 컬럼 모두 PK 구성 요소라 그 상태로는 서로 다른 두 태그가 PK 충돌을 일으키고,
-- component는 복합 PK(@IdClass)라 insert가 커밋 시점에 flush돼 SubVersionWriter의
-- catch(DataIntegrityViolationException) 밖에서 터진다 → 사용자 입력으로 500(E-9000)이 난다.
-- 자바 쪽 비교(Set.copyOf, equals)는 전부 정확 비교이므로 DB를 거기에 맞춘다.
--
-- 기존 행은 ai_ci 유일성을 이미 만족하므로 더 엄격한 _bin에서도 유일하다 — 재생성이 실패하지 않는다.
ALTER TABLE component
    MODIFY COLUMN image_tag VARCHAR(200) COLLATE utf8mb4_bin NOT NULL COMMENT '예: sb-cc-api:v2.0.25.8612';

-- component에서 확정 시점에 복사되므로 같은 대조여야 한다. 아니면 대소문자만 다른 컴포넌트 2건이
-- 매니페스트 확정에서 package_item PK 충돌로 죽는다.
ALTER TABLE package_item
    MODIFY COLUMN image_tag VARCHAR(200) COLLATE utf8mb4_bin NOT NULL COMMENT '패키징한 이미지 태그';
