package com.deployhub.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring MVC 표준 예외가 500(E-9000)으로 뭉개지지 않는지 본다.
 *
 * <p>{@code ExceptionHandlerExceptionResolver}가 {@code DefaultHandlerExceptionResolver}보다 먼저 돌기
 * 때문에, {@code @ExceptionHandler(Exception.class)} 하나가 400/405/415로 번역될 예외를 전부 가로챈다.
 * 아래 핸들러들을 지우면 이 테스트가 500으로 떨어져 실패한다.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 깨진_JSON은_400이다() throws Exception {
        mockMvc.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E-9002"));
    }

    @Test
    void 지원하지_않는_메서드는_405이고_Allow_헤더를_유지한다() throws Exception {
        mockMvc.perform(get("/test/echo"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
                .andExpect(jsonPath("$.code").value("E-9003"));
    }

    @Test
    void 받을_수_없는_Accept는_ERROR_로그를_남기지_않는다() throws Exception {
        // 상태코드는 수정 전에도 406이다(handleUnexpected가 만든 JSON을 못 실어 기본 리졸버로 떨어진다).
        // 문제는 무인증 API에 Accept 헤더 하나로 ERROR + 스택트레이스가 쌓이는 것이라 로그로 단언한다.
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mockMvc.perform(get("/test/json").accept(MediaType.APPLICATION_PDF))
                    .andExpect(status().isNotAcceptable());
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    @Test
    void 지원하지_않는_Content_Type은_415다() throws Exception {
        mockMvc.perform(post("/test/echo").contentType(MediaType.TEXT_PLAIN).content("name"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("E-9004"));
    }

    @Test
    void 필수_파라미터_누락은_400이고_파라미터명을_알려준다() throws Exception {
        mockMvc.perform(get("/test/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E-9002"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("keyword")));
    }

    /** 응답 본문에 예외 메시지·경로·스택트레이스가 새지 않는지 함께 본다. */
    @Test
    void 표준_예외_응답에는_기본_문구만_실린다() throws Exception {
        mockMvc.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(jsonPath("$.message").value(ErrorCode.MALFORMED_REQUEST.getMessage()))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @RestController
    static class TestController {

        @PostMapping(value = "/test/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
        String echo(@Valid @RequestBody Payload payload) {
            return payload.name();
        }

        @org.springframework.web.bind.annotation.GetMapping(
                value = "/test/json", produces = MediaType.APPLICATION_JSON_VALUE)
        Payload json() {
            return new Payload("ok");
        }

        @org.springframework.web.bind.annotation.GetMapping("/test/search")
        String search(@RequestParam String keyword) {
            return keyword;
        }
    }

    record Payload(@NotBlank String name) {}
}
