package jenkins.plugins.jclouds.compute;

import hudson.model.Descriptor;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;

import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Vijay Kiran
 */
public class JCloudsRetentionStrategy extends RetentionStrategy<JCloudsComputer> {
    private transient ReentrantLock checkLock;

    @DataBoundConstructor
    public JCloudsRetentionStrategy() {
        readResolve();
    }

    private void fastTerminate(final JCloudsComputer c) {
        if (!c.isOffline()) {
            LOGGER.info("Setting " + c.getName() + " to be deleted.");
            try {
                c.disconnect(OfflineCause.create(Messages._DeletedCause())).get();
            } catch (Exception e) {
                LOGGER.info("Caught " + e.toString());
            }
        }
        c.deleteSlave(true);
    }

    @Override
    public long check(JCloudsComputer c) {
        if (!checkLock.tryLock()) {
            return 1;
        } else {
            try {
                final JCloudsSlave node = c.getNode();
                // check isIdle() to ensure we are terminating busy slaves (including FlyWeight)
                if (null != node && c.isIdle()) {
                    if (node.isPendingDelete()) {
                        // Fixes JENKINS-28403
                        fastTerminate(c);
                    } else {
                        // Get the retention time, in minutes, from the JCloudsCloud this JCloudsComputer belongs to.
                        final int retentionTime = c.getRetentionTime();
                        if (retentionTime > -1) {
                            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
                            LOGGER.fine("Node " + c.getName() + " retentionTime: " + retentionTime + " idle: "
                                    + TimeUnit2.MILLISECONDS.toMinutes(idleMilliseconds) + "min");
                            if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(retentionTime)) {
                                LOGGER.info("Retention time for " + c.getName() + " has expired.");
                                node.setPendingDelete(true);
                                fastTerminate(c);
                            }
                        }
                    }
                }
            } finally {
                checkLock.unlock();
            }
        }
        return 1;
    }

    /**
     * Try to connect to it ASAP.
     */
    @Override
    public void start(JCloudsComputer c) {
        c.connect(false);
    }

    // no registration since this retention strategy is used only for cloud nodes that we provision automatically.
    // @Extension
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "JClouds";
        }
    }

    // Serialization
    protected Object readResolve() {
        checkLock = new ReentrantLock(false);
        return this;
    }

    private static final Logger LOGGER = Logger.getLogger(JCloudsRetentionStrategy.class.getName());
}
