package com.bosi.ykt.controller;

import com.bosi.ykt.common.BaseCrudController;
import com.bosi.ykt.entity.YktBank;
import com.bosi.ykt.mapper.YktBankMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 开户银行设置维护。手册 §五 */
@RestController
@RequestMapping("/setup/bank")
@RequiredArgsConstructor
public class YktBankController extends BaseCrudController<YktBankMapper, YktBank> {
    private final YktBankMapper mapper;
    @Override protected YktBankMapper getMapper() { return mapper; }
}
