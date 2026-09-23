package dev.jobhunter.scheduler;

import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import dev.jobhunter.service.RecruiterDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GdprPurgeSchedulerTest {

    /** Mirrors GdprPurgeScheduler.PURGE_BATCH_SIZE. */
    private static final int BATCH_SIZE = 2000;

    @Mock private RecruiterDataService recruiterDataService;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private OutreachContactRepository outreachContactRepository;
    @Mock private JobExecutionContext jobExecutionContext;

    @InjectMocks private GdprPurgeScheduler scheduler;

    @Test
    void purge_shortBatch_deletesOnceAndStops() throws JobExecutionException {
        List<UUID> ids = idsOfSize(2);
        when(jobPostingRepository.findPurgeableJobIds(any(LocalDate.class), eq(BATCH_SIZE))).thenReturn(ids);
        when(jobPostingRepository.deleteByIds(any())).thenReturn(ids.size());

        scheduler.execute(jobExecutionContext);

        verify(jobPostingRepository, times(1)).findPurgeableJobIds(any(LocalDate.class), eq(BATCH_SIZE));
        verify(outreachContactRepository, times(1)).deleteJobContactsByJobIds(ids);
        verify(jobPostingRepository, times(1)).deleteByIds(ids);
    }

    @Test
    void purge_fullBatches_loopsUntilShortBatch() throws JobExecutionException {
        List<UUID> fullBatch = idsOfSize(BATCH_SIZE);
        List<UUID> shortBatch = idsOfSize(3);
        when(jobPostingRepository.findPurgeableJobIds(any(LocalDate.class), eq(BATCH_SIZE)))
                .thenReturn(fullBatch, shortBatch);
        when(jobPostingRepository.deleteByIds(any())).thenReturn(BATCH_SIZE, 3);

        scheduler.execute(jobExecutionContext);

        verify(jobPostingRepository, times(2)).findPurgeableJobIds(any(LocalDate.class), eq(BATCH_SIZE));
        verify(outreachContactRepository).deleteJobContactsByJobIds(fullBatch);
        verify(outreachContactRepository).deleteJobContactsByJobIds(shortBatch);
        verify(jobPostingRepository).deleteByIds(fullBatch);
        verify(jobPostingRepository).deleteByIds(shortBatch);
    }

    @Test
    void purge_usesRetentionCutoff() throws JobExecutionException {
        when(jobPostingRepository.findPurgeableJobIds(any(LocalDate.class), eq(BATCH_SIZE))).thenReturn(List.of());

        scheduler.execute(jobExecutionContext);

        ArgumentCaptor<LocalDate> cutoffCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(jobPostingRepository).findPurgeableJobIds(cutoffCaptor.capture(), eq(BATCH_SIZE));
        assertThat(cutoffCaptor.getValue()).isEqualTo(LocalDate.now().minusDays(30));
    }

    @Test
    void purge_clearsJobContactsBeforeDeletingJobs() throws JobExecutionException {
        List<UUID> ids = idsOfSize(1);
        when(jobPostingRepository.findPurgeableJobIds(any(LocalDate.class), eq(BATCH_SIZE))).thenReturn(ids);
        when(jobPostingRepository.deleteByIds(any())).thenReturn(1);

        scheduler.execute(jobExecutionContext);

        InOrder inOrder = inOrder(outreachContactRepository, jobPostingRepository);
        inOrder.verify(outreachContactRepository).deleteJobContactsByJobIds(ids);
        inOrder.verify(jobPostingRepository).deleteByIds(ids);
    }

    @Test
    void purge_noPurgeableIds_deletesNothing() {
        when(jobPostingRepository.findPurgeableJobIds(any(LocalDate.class), eq(BATCH_SIZE))).thenReturn(List.of());

        assertDoesNotThrow(() -> scheduler.execute(jobExecutionContext));

        verify(outreachContactRepository, never()).deleteJobContactsByJobIds(any());
        verify(jobPostingRepository, never()).deleteByIds(any());
    }

    @Test
    void execute_repositoryFailure_doesNotPropagate() {
        when(jobPostingRepository.findPurgeableJobIds(any(LocalDate.class), anyInt()))
                .thenThrow(new RuntimeException("DB connection lost"));

        assertDoesNotThrow(() -> scheduler.execute(jobExecutionContext));
    }

    private static List<UUID> idsOfSize(int size) {
        return new ArrayList<>(IntStream.range(0, size).mapToObj(i -> UUID.randomUUID()).toList());
    }
}
