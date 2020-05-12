package io.metersphere.base.mapper.ext;

import io.metersphere.base.domain.LoadTestReport;
import io.metersphere.dto.ReportDTO;
import io.metersphere.performance.controller.request.ReportRequest;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ExtLoadTestReportMapper {

    List<ReportDTO> getReportList(@Param("reportRequest") ReportRequest request);

    ReportDTO getReportTestAndProInfo(@Param("id") String id);

    int appendLine(@Param("testId") String id, @Param("line") String line);

    LoadTestReport selectByPrimaryKey(String id);
}
