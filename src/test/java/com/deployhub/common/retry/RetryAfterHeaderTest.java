package com.deployhub.common.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class RetryAfterHeaderTest {

    @Test
    void 초_단위_값을_그대로_사용한다() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "10");

        assertThat(RetryAfterHeader.parseSeconds(headers)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void 상한을_넘는_값은_60초로_clamp한다() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "999999");

        assertThat(RetryAfterHeader.parseSeconds(headers)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void 음수나_0은_무시하고_null을_반환한다() {
        HttpHeaders negative = new HttpHeaders();
        negative.set(HttpHeaders.RETRY_AFTER, "-5");
        HttpHeaders zero = new HttpHeaders();
        zero.set(HttpHeaders.RETRY_AFTER, "0");

        assertThat(RetryAfterHeader.parseSeconds(negative)).isNull();
        assertThat(RetryAfterHeader.parseSeconds(zero)).isNull();
    }

    @Test
    void HTTP_date_형식은_지원하지_않고_null을_반환한다() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "Wed, 21 Oct 2026 07:28:00 GMT");

        assertThat(RetryAfterHeader.parseSeconds(headers)).isNull();
    }

    @Test
    void 헤더가_없으면_null을_반환한다() {
        assertThat(RetryAfterHeader.parseSeconds(new HttpHeaders())).isNull();
        assertThat(RetryAfterHeader.parseSeconds(null)).isNull();
    }
}
