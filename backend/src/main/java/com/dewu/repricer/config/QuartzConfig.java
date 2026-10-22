package com.dewu.repricer.config;

import com.dewu.repricer.task.ScheduledRepricingJob;
import org.quartz.*;
import org.springframework.context.annotation.*;

@Configuration
public class QuartzConfig {
    @Bean JobDetail repricingJob() { return JobBuilder.newJob(ScheduledRepricingJob.class).withIdentity("scheduledRepricing").storeDurably().build(); }
    @Bean Trigger repricingTrigger(JobDetail repricingJob, RepricerProperties p) {
        return TriggerBuilder.newTrigger().forJob(repricingJob).withIdentity("scheduledRepricingTrigger")
                .withSchedule(CronScheduleBuilder.cronSchedule(p.schedulerCron()).withMisfireHandlingInstructionDoNothing()).build();
    }
}
