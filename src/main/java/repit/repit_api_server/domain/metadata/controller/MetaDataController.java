package repit.repit_api_server.domain.metadata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import repit.repit_api_server.domain.metadata.dto.response.GenerateResponse;
import repit.repit_api_server.domain.metadata.dto.response.MetaDataResponse;
import repit.repit_api_server.domain.metadata.service.AnalysisLaunchService;
import repit.repit_api_server.domain.metadata.service.MetaService;
import repit.repit_api_server.global.auth.AuthUser;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/metaData")
public class MetaDataController {
    private final MetaService metaService;
    private final AnalysisLaunchService analysisLaunchService;

    @PostMapping(value = "/dataUpload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GenerateResponse> upload(@AuthenticationPrincipal AuthUser authUser,
                                                   @RequestPart("file") MultipartFile file,
                                                   @RequestParam List<String> gitUrls) throws IOException {
        MetaDataResponse metaData = metaService.dataUpload(authUser.token(), file, gitUrls);

        // 올린 자료로 곧바로 분석을 시작한다. 접수까지 함께 해야 이 분석에 주인이 남고,
        // 주인이 없으면 나중에 면접을 열 때 이 결과를 찾지 못한다.
        return ResponseEntity.ok(analysisLaunchService.launch(authUser.id(), metaData));
    }


    @GetMapping("/getMetaData")
    public ResponseEntity<MetaDataResponse> getMetaData(
            @AuthenticationPrincipal AuthUser authUser
    ) {
        return ResponseEntity.ok(metaService.getMetaData(authUser.token()));
    }


    @GetMapping("/isGit")
    public ResponseEntity<Boolean> isGit(
            @AuthenticationPrincipal AuthUser authUser
    ) {
        MetaDataResponse metaData = metaService.getMetaData(authUser.token());
        return ResponseEntity.ok(!metaData.getGitUrls().isEmpty());
    }

    @GetMapping("/isPortfolio")
    public ResponseEntity<Boolean> isPortfolio(
            @AuthenticationPrincipal AuthUser authUser
    ) {
        MetaDataResponse metaData = metaService.getMetaData(authUser.token());
        return ResponseEntity.ok(!metaData.getFileUrl().isEmpty());
    }
}
