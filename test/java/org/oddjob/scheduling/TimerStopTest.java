package org.oddjob.scheduling;

import java.text.ParseException;
import java.util.Date;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.mockito.Mockito;
import org.mockito.internal.matchers.CapturingMatcher;
import org.oddjob.FailedToStopException;
import org.oddjob.StateSteps;
import org.oddjob.Stoppable;
import org.oddjob.arooa.utils.DateHelper;
import org.oddjob.framework.SimpleJob;
import org.oddjob.jobs.job.StopJob;
import org.oddjob.schedules.Interval;
import org.oddjob.schedules.IntervalTo;
import org.oddjob.schedules.Schedule;
import org.oddjob.schedules.ScheduleContext;
import org.oddjob.schedules.ScheduleResult;
import org.oddjob.schedules.SimpleInterval;
import org.oddjob.schedules.SimpleScheduleResult;
import org.oddjob.schedules.schedules.DailySchedule;
import org.oddjob.state.JobState;
import org.oddjob.state.ParentState;

public class TimerStopTest extends TestCase {

	private static final Logger logger = Logger.getLogger(TimerStopTest.class);
	
	@Override
	protected void setUp() throws Exception {
		logger.info("----------------  " + getName() + "  ---------------");
	}
	
	private class NeverRan extends SimpleJob {
		@Override
		protected int execute() throws Throwable {
			throw new RuntimeException("Unexpected.");
		}
	}
	
	public void testStopBeforeRunning() throws InterruptedException, FailedToStopException {
		DefaultExecutors services = new DefaultExecutors();
		
		Timer test = new Timer();
		test.setScheduleExecutorService(
				services.getScheduledExecutor());
		test.setSchedule(new Schedule() {
			
			public IntervalTo nextDue(ScheduleContext context) {
				return new IntervalTo(Interval.END_OF_TIME);
			}
		});
		
		NeverRan job = new NeverRan();
				
		test.setJob(job);
	
		test.run();
		
		test.stop();
		
		assertEquals(JobState.READY, 
				job.lastStateEvent().getState());
		
		assertEquals(ParentState.READY, 
				test.lastStateEvent().getState());		
		
		
		services.stop();
	}
	
	private static class RunningJob extends SimpleJob 
	implements Stoppable{
		
		Exchanger<Void> running = new Exchanger<Void>();
		
		Thread t;
		
		@Override
		protected int execute() throws Throwable {
			t = Thread.currentThread();
			running.exchange(null);
			synchronized (this) {
				try {
					logger.debug("Running job waiting.");
					wait();
				}
				catch (InterruptedException e) {
					logger.debug("Running job itnerrupted.");
					Thread.currentThread().interrupt();
				}
			};
			return 0;
		}

		@Override
		protected void onStop() {
			synchronized (this) {
				t.interrupt();
			}
		}
	}	
	
	public void testStopWhenRunning() throws InterruptedException, FailedToStopException {
		DefaultExecutors services = new DefaultExecutors();
		
		Timer test = new Timer();
		test.setScheduleExecutorService(
				services.getScheduledExecutor());
		test.setSchedule(new Schedule() {
			
			public IntervalTo nextDue(ScheduleContext context) {
				return new IntervalTo(new Date());
			}
		});
		
		RunningJob job = new RunningJob();
				
		test.setJob(job);
	
		test.run();
		
		job.running.exchange(null);
		
		test.stop();

		assertFalse(Thread.interrupted());
		
		assertEquals(ParentState.READY, 
				test.lastStateEvent().getState());
		
		assertEquals(JobState.COMPLETE, 
				job.lastStateEvent().getState());
		
		services.stop();
	}
	
	private static class RunOnceJob extends SimpleJob {
		
		boolean ran;
		@Override
		protected int execute() throws Throwable {
			if (ran) {
				throw new Exception("Unexpected.");
			}
			return 0;
		}
	}	
	
	public void testStopBetweenSchedules() throws InterruptedException, Throwable {
		DefaultExecutors services = new DefaultExecutors();
		services.setPoolSize(5);
		
		final Timer test = new Timer();
		test.setScheduleExecutorService(
				services.getScheduledExecutor());
		test.setSchedule(new Schedule() {
			
			public IntervalTo nextDue(ScheduleContext context) {
				if (context.getData("done") == null) {
					context.putData("done", new Object());
					return new IntervalTo(new Date());
				}
				else {
					return new IntervalTo(Interval.END_OF_TIME);					
				}
			}
		});
		
		RunOnceJob job = new RunOnceJob();
		
		StopJob stop = new StopJob();
		stop.setJob(test);
		
		Trigger trigger = new Trigger();
		trigger.setExecutorService(services.getPoolExecutor());
		trigger.setJob(stop);
		trigger.setOn(job);

		trigger.run();
				
		test.setJob(job);
	
		StateSteps state = new StateSteps(test);
		
		state.startCheck(ParentState.READY, 
				ParentState.EXECUTING, ParentState.ACTIVE, ParentState.READY);
		
		logger.info("** First Run **");
		
		test.run();
		
		state.checkWait();
		
		assertEquals(JobState.COMPLETE, 
				job.lastStateEvent().getState());

		state.startCheck(ParentState.READY, 
				ParentState.EXECUTING, ParentState.ACTIVE);
		
		logger.info("** Second Run **");
		
		test.run();
		
		state.checkNow();
		
		assertEquals(new IntervalTo(Interval.END_OF_TIME), test.getCurrent());
		
		test.stop();
		
		services.stop();
	}

	/** Tracking down a bug with MissedSkipped when stop and started in 
	 * the current interval re-runs the current interval.
	 * @throws ParseException 
	 * @throws FailedToStopException 
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void testStopBetweenScheduleWithSkippedRuns() throws ParseException, FailedToStopException {
		
		final ScheduledFuture<?> future = Mockito.mock(ScheduledFuture.class);
		
		ScheduledExecutorService executor = 
				Mockito.mock(ScheduledExecutorService.class);
		
		CapturingMatcher<Runnable> runnable = 
				new CapturingMatcher<Runnable>();
		
		CapturingMatcher<Long> delay = new CapturingMatcher<Long>();
		
		Mockito.when(executor.schedule(
				Mockito.argThat(runnable), 
				Mockito.longThat(delay), 
				Mockito.eq(TimeUnit.MILLISECONDS))).thenReturn(
						(ScheduledFuture) future);
						
		Timer test = new Timer();
		test.setSchedule(new DailySchedule());
		test.setClock(new ManualClock("2012-01-24 01:00"));
		test.setScheduleExecutorService(executor);
		test.setJob(new RunOnceJob());
		test.setSkipMissedRuns(true);
		
		test.run();

		assertEquals(ParentState.ACTIVE, test.lastStateEvent().getState());
		
		ScheduleResult expectedCurrent1 = new SimpleScheduleResult(
				new SimpleInterval(
					DateHelper.parseDateTime("2012-01-24 00:00"),
					DateHelper.parseDateTime("2012-01-25 00:00"))); 
		
		assertEquals(new Long(0), delay.getLastValue());
		assertEquals(expectedCurrent1, test.getCurrent());
		
		runnable.getLastValue().run();
		
		ScheduleResult expectedCurrent2 = new SimpleScheduleResult(
				new SimpleInterval(
					DateHelper.parseDateTime("2012-01-25 00:00"),
					DateHelper.parseDateTime("2012-01-26 00:00"))); 
		
		assertEquals(expectedCurrent2, test.getCurrent());
		assertEquals(new Long(23 * 60 * 60 * 1000L), delay.getLastValue());
		
		test.stop();
		
		assertEquals(ParentState.READY, test.lastStateEvent().getState());
		
		test.run();
		
		assertEquals(ParentState.ACTIVE, test.lastStateEvent().getState());
		
		assertEquals(expectedCurrent2, test.getCurrent());
		assertEquals(new Long(23 * 60 * 60 * 1000L), delay.getLastValue());
		
		test.stop();
		
		assertEquals(ParentState.READY, test.lastStateEvent().getState());
		
		test.setJob(null);
		test.setJob(new RunOnceJob());
		test.setClock(new ManualClock("2012-01-28 01:00"));
		
		test.run();
		
		assertEquals(ParentState.ACTIVE, test.lastStateEvent().getState());
		
		ScheduleResult expectedCurrent3 = new SimpleScheduleResult(
				new SimpleInterval(
					DateHelper.parseDateTime("2012-01-28 00:00"),
					DateHelper.parseDateTime("2012-01-29 00:00"))); 
		
		assertEquals(new Long(0), delay.getLastValue());
		assertEquals(expectedCurrent3, test.getCurrent());
		
		runnable.getLastValue().run();
		
		ScheduleResult expectedCurrent4 = new SimpleScheduleResult(
				new SimpleInterval(
					DateHelper.parseDateTime("2012-01-29 00:00"),
					DateHelper.parseDateTime("2012-01-30 00:00"))); 
		
		assertEquals(expectedCurrent4, test.getCurrent());
		assertEquals(new Long(23 * 60 * 60 * 1000L), delay.getLastValue());
		
		test.stop();
		
		assertEquals(ParentState.READY, test.lastStateEvent().getState());
		
	}
}
