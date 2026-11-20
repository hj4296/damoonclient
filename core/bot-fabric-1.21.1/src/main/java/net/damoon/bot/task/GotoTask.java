package net.damoon.bot.task;

import net.damoon.bot.Bot;
import net.damoon.bot.Nav;
import net.damoon.bot.path.Goal;

public class GotoTask extends Task {
    private final Goal goal;

    public GotoTask(Goal goal) { this.goal = goal; }

    @Override public String name() { return "이동"; }

    @Override public String detail() { return goal.toString(); }

    @Override
    public S tick(Bot bot) {
        Nav.R r = bot.nav.go(goal);
        return r == Nav.R.ARRIVED ? S.DONE : r == Nav.R.FAILED ? S.FAILED : S.RUNNING;
    }
}
