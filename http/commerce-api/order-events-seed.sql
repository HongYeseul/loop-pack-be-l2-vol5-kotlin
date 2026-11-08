-- 주문 이벤트 관찰용 시드. 여러 번 실행해도 재고만 다시 채운다.
-- 회원가입 API가 없고 로컬 관리자 API는 인증 수단이 없어 SQL로 넣는다.

INSERT INTO user (id, created_at, updated_at, display_name, login_id, status)
VALUES (9001, NOW(6), NOW(6), '관찰용', 'observer1', 'ACTIVE')
ON DUPLICATE KEY UPDATE status = 'ACTIVE';

INSERT INTO brand (id, created_at, updated_at, deleted_at, name)
VALUES (9001, NOW(6), NOW(6), NULL, '관찰 브랜드')
ON DUPLICATE KEY UPDATE deleted_at = NULL;

INSERT INTO product (id, created_at, updated_at, deleted_at, brand_id, name, price, status, stock)
VALUES (9001, NOW(6), NOW(6), NULL, 9001, '관찰 상품 A', 1000, 'ON_SALE', 100),
       (9002, NOW(6), NOW(6), NULL, 9001, '관찰 상품 B', 2000, 'ON_SALE', 100)
ON DUPLICATE KEY UPDATE stock = 100, status = 'ON_SALE', deleted_at = NULL;
