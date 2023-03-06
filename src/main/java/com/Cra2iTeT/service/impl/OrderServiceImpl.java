package com.Cra2iTeT.service.impl;

import com.Cra2iTeT.domain.Order;
import com.Cra2iTeT.mapper.OrderMapper;
import com.Cra2iTeT.service.OrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * @author Cra2iTeT
 * @since 2023/3/5 23:42
 */
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {
}
