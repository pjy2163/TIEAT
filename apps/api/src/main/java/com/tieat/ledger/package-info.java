/**
 * 식대 이용 등록, 확인, 원장과 합계를 소유하는 핵심 bounded context.
 *
 * <p>외부 context의 모델을 직접 변경하지 않고 식대 이용 시점에 필요한 식별자와 금액 snapshot을
 * 자기 모델로 보존한다.</p>
 */
package com.tieat.ledger;
