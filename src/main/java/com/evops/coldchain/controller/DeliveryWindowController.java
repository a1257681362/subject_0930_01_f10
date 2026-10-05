package com.evops.coldchain.controller;

import com.evops.coldchain.dto.WindowCalendarRequest;
import com.evops.coldchain.dto.WindowCalendarVoidRequest;
import com.evops.coldchain.dto.WindowChangeRequest;
import com.evops.coldchain.dto.WindowCreateRequest;
import com.evops.coldchain.entity.DeliveryWindow;
import com.evops.coldchain.entity.DeliveryWindowCalendar;
import com.evops.coldchain.entity.DeliveryWindowVersion;
import com.evops.coldchain.entity.DeliveryWindowWeekday;
import com.evops.coldchain.service.DeliveryWindowService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 收货机构送达时窗管理（V10）。
 *
 * 两条相互独立的规则线在 URL 上也分开：
 * - 工作日可接收区间：建档 + “规则调整只能生成新版本”（/versions）；
 * - 节假日与临时闭馆：独立新增（/calendar）、只能作废留痕（/calendar/{ruleId}/void），
 *   不随工作日版本调整改动，也不允许原地修改/删除。
 */
@RestController
@RequestMapping("/api/cold-chain/delivery-windows")
public class DeliveryWindowController {

    private final DeliveryWindowService windowService;

    public DeliveryWindowController(DeliveryWindowService windowService) {
        this.windowService = windowService;
    }

    /** 建档：发布工作日区间 V1（节假日不在此接口，走独立日历规则接口） */
    @PostMapping("/create")
    public ApiResponse<DeliveryWindow> create(@Valid @RequestBody WindowCreateRequest req) {
        return ApiResponse.ok(windowService.create(req));
    }

    /** 工作日区间规则调整：只能生成新版本，旧版本与节假日规则旧行均不改 */
    @PostMapping("/{id}/versions")
    public ApiResponse<DeliveryWindowVersion> change(@PathVariable("id") Long id,
                                                     @Valid @RequestBody WindowChangeRequest req) {
        return ApiResponse.ok(windowService.change(id, req));
    }

    /** 独立新增节假日 / 临时闭馆规则（不产生工作日新版本） */
    @PostMapping("/{id}/calendar")
    public ApiResponse<DeliveryWindowCalendar> addCalendarRule(@PathVariable("id") Long id,
                                                               @Valid @RequestBody WindowCalendarRequest req) {
        return ApiResponse.ok(windowService.addCalendarRule(id, req));
    }

    /** 作废节假日 / 临时闭馆规则（只置 REVOKED 留痕，不删行、不改原值） */
    @PostMapping("/calendar/{ruleId}/void")
    public ApiResponse<DeliveryWindowCalendar> voidCalendarRule(@PathVariable("ruleId") Long ruleId,
                                                                @Valid @RequestBody WindowCalendarVoidRequest req) {
        return ApiResponse.ok(windowService.voidCalendarRule(ruleId, req.getVoidReason()));
    }

    /** 时窗主档列表（可按收货机构精确过滤） */
    @GetMapping
    public ApiResponse<List<DeliveryWindow>> list(@RequestParam(value = "receiverOrg", required = false)
                                                              String receiverOrg) {
        if (receiverOrg != null && !receiverOrg.trim().isEmpty()) {
            DeliveryWindow one = windowService.findByReceiverOrg(receiverOrg.trim());
            return ApiResponse.ok(one == null ? java.util.Collections.emptyList()
                    : java.util.Collections.singletonList(one));
        }
        return ApiResponse.ok(windowService.listAll());
    }

    /** 时窗主档详情（含当前版本号） */
    @GetMapping("/{id}")
    public ApiResponse<DeliveryWindow> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(windowService.requireWindow(id));
    }

    /** 版本链（工作日区间版本只追加，历史版本可追查） */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<DeliveryWindowVersion>> versions(@PathVariable("id") Long id) {
        return ApiResponse.ok(windowService.listVersions(id));
    }

    /** 某版本的工作日可接收区间（历史版本原值） */
    @GetMapping("/{id}/versions/{versionNo}/weekdays")
    public ApiResponse<List<DeliveryWindowWeekday>> weekdays(@PathVariable("id") Long id,
                                                             @PathVariable("versionNo") Integer versionNo) {
        DeliveryWindowVersion version = windowService.requireVersion(id, versionNo);
        return ApiResponse.ok(windowService.listWeekdays(version.getId()));
    }

    /** 节假日与临时闭馆规则全集（含已作废行，旧结论可追查） */
    @GetMapping("/{id}/calendar")
    public ApiResponse<List<DeliveryWindowCalendar>> calendar(@PathVariable("id") Long id) {
        return ApiResponse.ok(windowService.listCalendar(id));
    }
}
