package com.shiwushop.common.captcha;

import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.resource.ResourceStore;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import cloud.tianai.captcha.resource.impl.LocalMemoryResourceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CaptchaResourceConfiguration {
    /** 装载滑块底图资源；挑战答案仍由共享存储维护。 */
    @Bean
    ResourceStore captchaResourceStore() {
        LocalMemoryResourceStore store = new LocalMemoryResourceStore();
        // Each instance carries the same image; challenge answers remain in shared Redis.
        store.addResource(CaptchaTypeConstant.SLIDER,
                new Resource("classpath", "META-INF/cut-image/resource/1.jpg"));
        return store;
    }
}
