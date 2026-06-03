import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.PrintWriter;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * Minimal Maven-free JUnit 5 runner. Usage: {@code java JdocTestRunner <fully.qualified.TestClass>}.
 * Runs every @Test in the class, prints a summary, and exits 1 if anything failed.
 * Used by scripts/verify_jdoc_test.py to verify throwaway Javadoc-example tests in isolation.
 */
public class JdocTestRunner {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: JdocTestRunner <fully.qualified.TestClass>");
            System.exit(2);
        }
        final LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(args[0]))
                .build();
        final Launcher launcher = LauncherFactory.create();
        final SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        final TestExecutionSummary summary = listener.getSummary();
        final PrintWriter out = new PrintWriter(System.out, true);
        summary.printTo(out);
        if (summary.getTotalFailureCount() > 0) {
            summary.printFailuresTo(out, 100);
            out.flush();
            System.out.println("RESULT: FAIL (" + summary.getTotalFailureCount() + " failure(s))");
            System.exit(1);
        }
        out.flush();
        System.out.println("RESULT: PASS (" + summary.getTestsSucceededCount() + " test(s))");
    }
}
