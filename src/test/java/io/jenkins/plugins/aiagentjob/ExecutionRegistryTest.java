package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.util.List;

public class ExecutionRegistryTest {

    @Test
    public void pendingApproval_createdWithCorrectFields() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        ExecutionRegistry.PendingApproval pending =
                live.createPendingApproval("tc-1", "bash", "ls -la");

        assertEquals("tc-1", pending.getToolCallId());
        assertEquals("bash", pending.getToolName());
        assertEquals("ls -la", pending.getInputSummary());
        assertNotNull(pending.getId());
        assertNotNull(pending.getCreatedAt());
    }

    @Test
    public void pendingApprovals_listedCorrectly() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        live.createPendingApproval("tc-1", "bash", "ls");
        live.createPendingApproval("tc-2", "read", "file.txt");

        List<ExecutionRegistry.PendingApproval> approvals = live.getPendingApprovals();
        assertEquals(2, approvals.size());
    }

    @Test
    public void approve_resolvesDecision() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        ExecutionRegistry.PendingApproval pending =
                live.createPendingApproval("tc-1", "bash", "ls");

        // Approve in another thread
        new Thread(
                        () -> {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            live.approve(pending.getId());
                        })
                .start();

        ExecutionRegistry.ApprovalDecision decision =
                live.awaitDecision(pending, Duration.ofSeconds(5));
        assertTrue("Should be approved", decision.isApproved());
    }

    @Test
    public void deny_resolvesDecision() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        ExecutionRegistry.PendingApproval pending =
                live.createPendingApproval("tc-1", "bash", "rm -rf /");

        new Thread(
                        () -> {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            live.deny(pending.getId(), "dangerous command");
                        })
                .start();

        ExecutionRegistry.ApprovalDecision decision =
                live.awaitDecision(pending, Duration.ofSeconds(5));
        assertFalse("Should be denied", decision.isApproved());
        assertEquals("dangerous command", decision.getReason());
    }

    @Test
    public void timeout_deniesDecision() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        ExecutionRegistry.PendingApproval pending =
                live.createPendingApproval("tc-1", "bash", "ls");

        ExecutionRegistry.ApprovalDecision decision =
                live.awaitDecision(pending, Duration.ofMillis(100));
        assertFalse("Should be denied on timeout", decision.isApproved());
        assertTrue(
                "Reason should mention timed out",
                decision.getReason().toLowerCase().contains("timed out")
                        || decision.getReason().toLowerCase().contains("timeout"));
    }

    @Test
    public void approvalDecision_factoryMethods() {
        ExecutionRegistry.ApprovalDecision approved = ExecutionRegistry.ApprovalDecision.approved();
        assertTrue(approved.isApproved());

        ExecutionRegistry.ApprovalDecision denied =
                ExecutionRegistry.ApprovalDecision.denied("reason");
        assertFalse(denied.isApproved());
        assertEquals("reason", denied.getReason());
    }

    @Test
    public void approve_returnsFalseForUnknownId() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        assertFalse(live.approve("nonexistent-id"));
    }

    @Test
    public void deny_returnsFalseForUnknownId() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        assertFalse(live.deny("nonexistent-id", "reason"));
    }
}
