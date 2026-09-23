package jp.hyperliquid.taxtracker.price;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CoinGeckoPriceProviderTest {

    @Test
    void parsesMillisecondPricePointsAndPreservesRawResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://prices.test/coins/solana/market_chart/range"
                        + "?vs_currency=jpy&from=1790035200&to=1790038800&interval=hourly&precision=full"))
                .andRespond(withSuccess("{\"prices\":[[1790035200123,24567.890123],[1790038800000,24600.12]]}",
                        MediaType.APPLICATION_JSON));

        CoinGeckoPriceProvider provider = new CoinGeckoPriceProvider(builder, "http://prices.test", "");
        PriceProvider.PriceResponse response = provider.fetch(
                "solana", "jpy", Instant.ofEpochSecond(1790035200),
                Instant.ofEpochSecond(1790038800), "hourly");

        assertThat(response.rawPayload().path("prices")).hasSize(2);
        assertThat(response.points()).hasSize(2);
        assertThat(response.points().get(0).timestamp()).isEqualTo(Instant.ofEpochMilli(1790035200123L));
        assertThat(response.points().get(0).price()).hasToString("24567.890123");
        server.verify();
    }
}
