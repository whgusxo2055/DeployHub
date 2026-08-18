package com.deployhub.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.ItemErrorCode;
import com.deployhub.registry.ImageTagChecker.TagCheck;
import com.deployhub.registry.NcrRegistryClient.ManifestInfo;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 등록 차단(E-0206)과 Job 검증(E-0501)이 공유하는 판정. {@code SubVersionRegistryCheckTest}는 이
 * 클래스를 통째로 mock하므로 여기 판정은 그쪽에서 한 줄도 실행되지 않는다 — 가장 틀리기 쉬운
 * 자리라 따로 고정한다. 실행기는 호출 스레드로 대체해 병렬성만 뺀다.
 */
@ExtendWith(MockitoExtension.class)
class ImageTagCheckerTest {

    private static final String TAG = "acme/cc-sb:v1.0.108";

    @Mock
    private NcrRegistryClient ncrRegistryClient;

    private ImageTagChecker checker() {
        return new ImageTagChecker(ncrRegistryClient, Runnable::run, 5);
    }

    /** 404만 "확실히 없음"이다 — 이 플래그가 등록을 차단할지 말지를 가른다. */
    @Test
    void 레지스트리가_404면_확실히_없음으로_표시한다() {
        when(ncrRegistryClient.getManifest(any())).thenReturn(Optional.empty());

        TagCheck check = checker().checkAll(List.of(TAG)).get(0);

        assertThat(check.found()).isFalse();
        assertThat(check.definitelyMissing()).isTrue();
        assertThat(check.failureCode()).isEqualTo(ItemErrorCode.IMAGE_NOT_FOUND);
    }

    /** 타임아웃은 "확인 불가"다 — 확실히 없음으로 올리면 레지스트리 장애가 등록 불가로 번진다. */
    @Test
    void 타임아웃은_항목_실패로_강등하되_확실히_없음은_아니다() {
        when(ncrRegistryClient.getManifest(any())).thenThrow(new ApiException(ErrorCode.REGISTRY_TIMEOUT));

        TagCheck check = checker().checkAll(List.of(TAG)).get(0);

        assertThat(check.definitelyMissing()).isFalse();
        assertThat(check.failureCode()).isEqualTo(ItemErrorCode.MANIFEST_LOOKUP_TIMEOUT);
    }

    /**
     * 사내망 차단은 토큰 응답에 평문이 끼어 E-0404로 분류된다 — 이걸 던지면 차단 상황에서
     * 등록이 전면 실패하고, 인덱스 이미지 하나가 형제 태그 검증까지 중단시킨다.
     */
    @Test
    void 도달_불가도_항목_실패로_강등한다() {
        when(ncrRegistryClient.getManifest(any())).thenThrow(new ApiException(ErrorCode.REGISTRY_UNREACHABLE));

        TagCheck check = checker().checkAll(List.of(TAG)).get(0);

        assertThat(check.definitelyMissing()).isFalse();
        assertThat(check.failureCode()).isEqualTo(ItemErrorCode.MANIFEST_LOOKUP_UNAVAILABLE);
    }

    /** 자격증명 문제를 "이미지 없음"으로 뭉개면 운영자가 원인을 못 찾는다 — 이것만 올린다. */
    @Test
    void 인증_실패는_강등하지_않고_그대로_던진다() {
        when(ncrRegistryClient.getManifest(any())).thenThrow(new ApiException(ErrorCode.REGISTRY_UNAUTHORIZED));

        assertThatThrownBy(() -> checker().checkAll(List.of(TAG)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRY_UNAUTHORIZED);
    }

    /** 형식 오류 메시지에 태그 원문이 실리면 무인증 응답으로 그대로 나간다. */
    @Test
    void 형식_오류는_코드가_정한_문구만_남긴다() {
        TagCheck check = checker().checkAll(List.of("ACME/X:1")).get(0);

        assertThat(check.failureCode()).isEqualTo(ItemErrorCode.INVALID_IMAGE_TAG);
        assertThat(check.failureCode().toErrorMessage()).doesNotContain("ACME/X");
    }

    /** PackageValidationService가 결과를 인덱스로 짝짓는다 — 순서가 밀리면 엉뚱한 항목이 실패한다. */
    @Test
    void 결과는_입력_순서를_유지한다() {
        when(ncrRegistryClient.getManifest(any()))
                .thenReturn(Optional.of(new ManifestInfo("sha256:a", 1L)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ManifestInfo("sha256:c", 3L)));

        List<TagCheck> checks = checker().checkAll(List.of("acme/a:1", "acme/b:1", "acme/c:1"));

        assertThat(checks).extracting(TagCheck::imageTag).containsExactly("acme/a:1", "acme/b:1", "acme/c:1");
        assertThat(checks).extracting(TagCheck::found).containsExactly(true, false, true);
    }
}
