package com.autocare.server.task;

import java.util.function.Consumer;

/** prepare 在资源事务外做耗时工作，返回一个只保存结果的短事务步骤。 */
public interface JobHandler {
  String type();
  Consumer<JobRow> prepare(JobLease lease, JobRow job) throws Exception;
}
