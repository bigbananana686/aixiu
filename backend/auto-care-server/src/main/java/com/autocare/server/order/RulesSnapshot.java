package com.autocare.server.order;

/**
 * 规则快照（契约 §2.4）。创建订单时把当时的规则参数存进订单，之后改配置不悄悄改历史订单
 * （设计 §3.1：这些是可配置演示参数，本片取默认值 C=30／B1=15／B2=15／10／15，配置化归 G2/G4）。
 */
public record RulesSnapshot(
        int commuteMinutes,
        int bufferBeforeMinutes,
        int bufferAfterMinutes,
        int quoteConfirmWindowMinutes,
        int acceptWindowMinutes) {

    /** v1 的默认规则值（设计 §3.1）。通勤以外都以这里为准；通勤跟着技师行走。 */
    public static final RulesSnapshot DEFAULT = new RulesSnapshot(30, 15, 15, 10, 15);
}
