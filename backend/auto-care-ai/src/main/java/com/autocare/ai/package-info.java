/**
 * auto-care 的 AI 能力模块：对业务暴露稳定接口，Spring AI 的实现藏在适配器里。
 *
 * <p>包边界规则（设计 §5.1、§9.1）：接口签名上只允许出现业务 DTO，
 * 不允许出现任何 {@code org.springframework.ai.*} 类型——换模型、换框架都不该改到调用方。
 * 本模块不依赖 {@code auto-care-server}；业务侧的数据通过只读端口注入，避免循环依赖。
 */
package com.autocare.ai;
