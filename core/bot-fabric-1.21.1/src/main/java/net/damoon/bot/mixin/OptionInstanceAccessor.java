package net.damoon.bot.mixin;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 밝기 옵션의 범위 검사(0~1)를 우회해서 값을 직접 넣기 위한 접근자 (감마/풀브라이트) */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {
    @Accessor("value")
    void damoonbot$setValue(Object value);
}
