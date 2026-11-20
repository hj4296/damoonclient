package net.damoon.bot.task;

import net.damoon.bot.Bot;

/** 봇이 하는 일 하나. 매 틱 tick()을 부르고, 끝나면 DONE/FAILED. */
public abstract class Task {
    public enum S { RUNNING, DONE, FAILED }

    public abstract String name();

    public abstract S tick(Bot bot);

    /** HUD에 보여줄 세부 상태 */
    public String detail() { return ""; }
}
