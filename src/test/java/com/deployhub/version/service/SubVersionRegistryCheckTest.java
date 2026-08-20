package com.deployhub.version.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.registry.ImageTagChecker;
import com.deployhub.registry.ImageTagChecker.TagCheck;
import com.deployhub.registry.NcrRegistryClient.ManifestInfo;
import com.deployhub.version.dto.SubVersionSavedResponse;
import com.deployhub.version.dto.SubVersionUpsertRequest;
import com.deployhub.version.entity.SubmitStatus;
import com.deployhub.version.entity.Component;
import com.deployhub.version.entity.SubVersion;
import com.deployhub.version.repository.ComponentRepository;
import com.deployhub.version.repository.MainVersionRepository;
import com.deployhub.version.repository.SubVersionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 서브버전 등록 시점의 레지스트리 존재 확인(E-0206). */
@ExtendWith(MockitoExtension.class)
class SubVersionRegistryCheckTest {

    private static final String VERSION = "2026.08.05";
    private static final String TAG = "acme/cc-sb:v1.0.108";

    @Mock
    private MainVersionRepository mainVersionRepository;

    @Mock
    private SubVersionRepository subVersionRepository;

    @Mock
    private ComponentRepository componentRepository;

    @Mock
    private ManifestLockGuard manifestLockGuard;

    @Mock
    private SubVersionWriter subVersionWriter;

    @Mock
    private ImageTagChecker imageTagChecker;

    private SubVersionService service(boolean verifyOnRegister) {
        return new SubVersionService(
                mainVersionRepository,
                subVersionRepository,
                componentRepository,
                manifestLockGuard,
                subVersionWriter,
                imageTagChecker,
                verifyOnRegister);
    }

    private static SubVersionUpsertRequest request() {
        return new SubVersionUpsertRequest("cc", "v1.0.108", null, 0, SubmitStatus.PENDING, List.of(TAG));
    }

    /** 이 단언이 수정 전 코드(검증 없음)에서는 실패한다 — 저장이 그대로 성공하기 때문이다. */
    @Test
    void 레지스트리에_없는_태그는_저장하지_않고_E_0206으로_거부한다() {
        when(mainVersionRepository.existsById(VERSION)).thenReturn(true);
        when(imageTagChecker.checkAll(List.of(TAG)))
                .thenReturn(List.of(new TagCheck(TAG, null, ErrorCode.IMAGE_NOT_FOUND, true)));

        assertThatThrownBy(() -> service(true).upsert(VERSION, request()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getErrorCode()).isEqualTo(ErrorCode.IMAGE_TAG_NOT_IN_REGISTRY);
                    assertThat(api.getDetails()).containsExactly(TAG);
                });
        // 저장 구간에 아예 들어가지 않는다 — 트랜잭션도 열리지 않는다.
        verifyNoInteractions(subVersionWriter);
    }

    /** 타임아웃·차단은 "확인 불가"라 등록을 막지 않는다 — 막으면 레지스트리 장애가 등록 불가로 번진다. */
    @Test
    void 확인_불가는_등록을_막지_않는다() {
        stubSave();
        when(imageTagChecker.checkAll(List.of(TAG)))
                .thenReturn(List.of(new TagCheck(TAG, null, ErrorCode.MANIFEST_LOOKUP_TIMEOUT, false)));

        assertThat(service(true).upsert(VERSION, request()).imageTags()).containsExactly(TAG);
        verify(subVersionWriter).save(eq(VERSION), any(), any());
    }

    @Test
    void 존재하는_태그는_그대로_저장한다() {
        stubSave();
        when(imageTagChecker.checkAll(List.of(TAG)))
                .thenReturn(List.of(new TagCheck(TAG, new ManifestInfo("sha256:abc", 100L), null, false)));

        assertThat(service(true).upsert(VERSION, request()).imageTags()).containsExactly(TAG);
    }

    @Test
    void 확인이_꺼져_있으면_레지스트리를_아예_부르지_않는다() {
        stubSave();

        assertThat(service(false).upsert(VERSION, request()).imageTags()).containsExactly(TAG);
        verifyNoInteractions(imageTagChecker);
    }

    /** 한 서브버전 안의 중복은 유일성 검사를 통과해 DB 1건 / 응답 2건으로 갈렸다. */
    @Test
    void 한_서브버전_안의_중복_태그도_거절한다() {
        when(mainVersionRepository.existsById(VERSION)).thenReturn(true);

        assertThatThrownBy(() -> service(false)
                        .upsert(VERSION, new SubVersionUpsertRequest("cc", "v1", null, 0, SubmitStatus.PENDING, List.of(TAG, TAG))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.SUB_VERSION_VALIDATION_FAILED);
        verifyNoInteractions(subVersionWriter);
    }

    private void stubSave() {
        when(mainVersionRepository.existsById(VERSION)).thenReturn(true);
        when(subVersionWriter.save(eq(VERSION), any(), any()))
                .thenReturn(SubVersionSavedResponse.builder()
                        .id(1L)
                        .code("cc")
                        .version("v1.0.108")
                        .sortOrder(0)
                        .imageTags(List.of(TAG))
                        .build());
    }
}
