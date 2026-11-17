package poc.compute.client;

import java.util.List;
import java.util.Map;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.compute.JobDescriptor;
import org.apache.ignite.compute.JobTarget;
import org.apache.ignite.deployment.DeploymentUnit;
import org.apache.ignite.table.Tuple;
import poc.compute.jobs.Json;

/** Calls the deployed jobs: JSON in, JSON out. Per-customer jobs run on the node that holds the customer's partition. */
public final class Jobs {
    static final String PKG = "poc.compute.jobs.";
    /** The deployment unit holding the jobs (phase2/deploy.sh); "latest" always picks the newest deployed version. */
    public static final DeploymentUnit UNIT = new DeploymentUnit("customer360-jobs", "latest");

    private Jobs() {}

    private static JobDescriptor<String, String> descriptor(String job) {
        return JobDescriptor.<String, String>builder(PKG + job).units(UNIT).build();
    }

    /** Run a job on the node that owns the customer. */
    public static Object forCustomer(IgniteClient c, String job, int customerId, Map<String, Object> arg) {
        JobTarget t = JobTarget.colocated("CUSTOMER", Tuple.create().set("CUSTOMER_ID", customerId));
        return Json.read(c.compute().execute(t, descriptor(job), Json.write(arg)));
    }

    /** Run a job on any node (work that is not tied to one customer). */
    public static Object anywhere(IgniteClient c, String job, Map<String, Object> arg) {
        return Json.read(c.compute().execute(JobTarget.anyNode(c.cluster().nodes()), descriptor(job), Json.write(arg)));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object result) {
        return (Map<String, Object>) result;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object result) {
        return (List<Object>) result;
    }
}
