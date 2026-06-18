-- /그림(imagine) 결과를 공개 채널에 올리기 전 본인 확인 게이트 설정(차수: 본인 확인 게이트).
-- 행 존재 = 그 유저는 확인 게이트 OFF(끄기, 바로 게시). 행 부재 = 기본 ON(완성 후 ephemeral 미리보기 + 게시 확인 버튼).
-- 끈 유저만 행을 저장한다(대다수가 기본 ON 이라 테이블이 작게 유지됨). BlocklistService 와 같은 DB+캐시 패턴.
CREATE TABLE imagine_post_confirm_off (
    user_id    BIGINT NOT NULL PRIMARY KEY,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
