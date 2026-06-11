package com.discordassistant.central.licensing

import com.discordassistant.central.licensing.adapter.outbound.PaddleCheckoutAdapter
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** ENV 미설정(api-key/price-id 빈값)이면 외부 호출 없이 null(결제 비활성). */
class PaddleCheckoutAdapterTest {
    @Test
    fun `api-key·price-id 없으면 null(비활성)`() {
        assertNull(PaddleCheckoutAdapter(env = "sandbox", apiKey = "", priceId = "").createCheckoutUrl(1L))
        assertNull(PaddleCheckoutAdapter(env = "sandbox", apiKey = "k", priceId = "").createCheckoutUrl(1L))
        assertNull(PaddleCheckoutAdapter(env = "sandbox", apiKey = "", priceId = "p").createCheckoutUrl(1L))
    }
}
