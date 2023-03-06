package com.Cra2iTeT.service.impl;

import com.Cra2iTeT.domain.LinkClick;
import com.Cra2iTeT.mapper.LinkClickMapper;
import com.Cra2iTeT.mapper.LinkMapper;
import com.Cra2iTeT.service.LinkClickService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * @author Cra2iTeT
 * @since 2023/3/6 20:03
 */
@Service
public class LinkClickServiceImpl extends ServiceImpl<LinkClickMapper, LinkClick> implements LinkClickService {
}
