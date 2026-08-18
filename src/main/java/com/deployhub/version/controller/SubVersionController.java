package com.deployhub.version.controller;

import com.deployhub.version.dto.SubmitStatusChangeRequest;
import com.deployhub.version.service.SubVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서브버전을 자기 id로 다루는 작업(삭제·제출 상태 변경)만 둔다.
 * 메인버전 경로에 종속된 일괄 등록은 {@link MainVersionController}가 맡는다.
 */
@RestController
@Tag(name = "버전 및 매니페스트 관리")
@RequiredArgsConstructor
public class SubVersionController {

    private final SubVersionService subVersionService;

    @Operation(summary = "서브버전 삭제 (컴포넌트 함께 삭제)")
    @ApiResponse(responseCode = "404", description = "E-0201: 서브버전 없음")
    @ApiResponse(responseCode = "409", description = "E-0204: Job이 있는 메인버전은 삭제 불가")
    @DeleteMapping("/api/sub-versions/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        subVersionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "담당 영역 업데이트 상태 변경 (FN-02-1)")
    @ApiResponse(responseCode = "400", description = "E-0205: 요청 값 검증 실패")
    @ApiResponse(responseCode = "404", description = "E-0201: 서브버전 없음")
    @PatchMapping("/api/sub-versions/{id}/submit-status")
    public ResponseEntity<Void> changeSubmitStatus(
            @PathVariable Long id, @Valid @RequestBody SubmitStatusChangeRequest request) {
        subVersionService.changeSubmitStatus(id, request);
        return ResponseEntity.noContent().build();
    }
}
