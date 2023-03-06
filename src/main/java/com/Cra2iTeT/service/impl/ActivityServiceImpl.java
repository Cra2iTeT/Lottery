package com.Cra2iTeT.service.impl;

import com.Cra2iTeT.domain.Activity;
import com.Cra2iTeT.mapper.ActivityMapper;
import com.Cra2iTeT.service.ActivityService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * @author Cra2iTeT
 * @since 2023/3/5 23:42
 */
@Service
public class ActivityServiceImpl extends ServiceImpl<ActivityMapper, Activity> implements ActivityService {
}
