package poc.api.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.compute.IgniteCompute;
import org.apache.ignite.compute.JobDescriptor;
import org.apache.ignite.compute.JobTarget;
import org.apache.ignite.network.ClusterNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import poc.api.model.Dto.*;
import poc.api.model.TravelDto.*;
import poc.api.web.ApiExceptions.*;

class ComputeServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();

    IgniteClient client = mock(IgniteClient.class);
    IgniteCompute compute = mock(IgniteCompute.class);
    ComputeService service = new ComputeService(client);

    @BeforeEach
    void wire() {
        when(client.compute()).thenReturn(compute);
        var cluster = mock(org.apache.ignite.network.IgniteCluster.class);       // jobs that are not tied to a customer run on any node
        when(client.cluster()).thenReturn(cluster);
        when(cluster.nodes()).thenReturn(List.of(mock(ClusterNode.class)));
    }

    /** The job returns this JSON for every call. */
    private void jobReturns(String json) {
        when(compute.execute(any(JobTarget.class), any(JobDescriptor.class), anyString())).thenReturn(json);
    }

    private static String ok(String fields) {
        return "{\"rc\":0" + (fields.isEmpty() ? "" : "," + fields) + "}";
    }

    private static String fail(int errorNo, String message) {
        return "{\"rc\":-1,\"error_no\":" + errorNo + ",\"error\":\"" + message + "\"}";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentArguments() throws Exception {
        ArgumentCaptor<String> arg = ArgumentCaptor.forClass(String.class);
        verify(compute, atLeastOnce()).execute(any(JobTarget.class), any(JobDescriptor.class), arg.capture());
        return JSON.readValue(arg.getValue(), Map.class);
    }

    private String jobClassCalled() {
        ArgumentCaptor<JobDescriptor<String, String>> d = ArgumentCaptor.forClass(JobDescriptor.class);
        verify(compute, atLeastOnce()).execute(any(JobTarget.class), d.capture(), anyString());
        return d.getValue().jobClassName();
    }

    @Test
    void customer360StripsTheReturnCodeAndCallsTheJobInTheDeploymentUnit() throws Exception {
        jobReturns(ok("\"profile\":[{\"customer_id\":7}]"));
        Map<String, Object> r = service.customer360(7);
        assertFalse(r.containsKey("rc"));
        assertTrue(r.containsKey("profile"));
        assertEquals("poc.compute.jobs.Customer360Job", jobClassCalled());
        assertEquals(Map.of("customer_id", 7), sentArguments());
        ArgumentCaptor<JobDescriptor<String, String>> d = ArgumentCaptor.forClass(JobDescriptor.class);
        verify(compute).execute(any(JobTarget.class), d.capture(), anyString());
        assertEquals("customer360-jobs", d.getValue().units().get(0).name());
        assertEquals("latest", d.getValue().units().get(0).version().render());
    }

    @Test
    void jobErrorNumbersMapToHttpExceptions() {
        for (int notFound : new int[] {20002, 20103, 20110}) {
            jobReturns(fail(notFound, "x"));
            assertEquals("Customer 7 not found", assertThrows(NotFound.class, () -> service.customer360(7)).getMessage());
        }
        for (int conflict : new int[] {20003, 20106, 20107, 20111, 20112, 20121}) {
            jobReturns(fail(conflict, "business rule " + conflict));
            assertEquals("business rule " + conflict, assertThrows(Conflict.class, () -> service.customer360(7)).getMessage());
        }
        for (int bad : new int[] {20001, 20104, 20105, 20120, 12345}) {
            jobReturns(fail(bad, "bad input"));
            assertThrows(BadRequest.class, () -> service.customer360(7));
        }
    }

    @Test
    void recalculateTier() {
        jobReturns(ok("\"tier_after\":\"GOLD\""));
        assertEquals(Map.of("tier_after", "GOLD"), service.recalculateTier(7));
    }

    @Test
    void scoreCustomersPassesAsOfThrough() throws Exception {
        jobReturns("[{\"risk_band\":\"LOW\",\"customers\":3,\"avg_score\":9}]");
        List<Object> r = service.scoreCustomers(5L);
        assertEquals(1, r.size());
        assertEquals("poc.compute.jobs.ScoreCustomersJob", jobClassCalled());
        assertEquals(Map.of("as_of", 5), sentArguments());
    }

    @Test
    void placeOrderFlattensLinesAndAppliesDefaults() throws Exception {
        jobReturns(ok("\"order_id\":1000000005,\"customer_id\":7,\"total_amount\":1560.00,\"points\":1560,\"tier_before\":\"GOLD\",\"tier_after\":\"PLATINUM\",\"loyalty_points\":61000"));
        PlaceOrderResult out = service.placeOrder(new PlaceOrderRequest(7, null, null, List.of(new OrderLine(1, 2), new OrderLine(13, 1))));
        assertEquals(1000000005, out.orderId());
        assertEquals("PLATINUM", out.tierAfter());
        assertEquals(new BigDecimal("1560.00"), out.totalAmount());
        assertEquals("poc.compute.jobs.PlaceOrderJob", jobClassCalled());
        assertEquals(Map.of("customer_id", 7, "channel", "WEB", "payment_method", "CARD", "items", List.of(1, 2, 13, 1)), sentArguments());
    }

    @Test
    void placeOrderKeepsExplicitChannelAndMethod() throws Exception {
        jobReturns(ok("\"order_id\":1,\"customer_id\":7,\"total_amount\":1,\"points\":1,\"tier_before\":\"BLUE\",\"tier_after\":\"BLUE\",\"loyalty_points\":1"));
        service.placeOrder(new PlaceOrderRequest(7, "APP", "POINTS", List.of(new OrderLine(1, 1))));
        Map<String, Object> sent = sentArguments();
        assertEquals("APP", sent.get("channel"));
        assertEquals("POINTS", sent.get("payment_method"));
    }

    @Test
    void wholeNumbersFromTheJobStillComeBackAsDecimalsWhereTheApiPromisesThem() {
        jobReturns(ok("\"booking_id\":9,\"refund\":0,\"segments_cancelled\":1,\"booking_status\":\"CANCELLED\""));
        CancelBookingResult r = service.cancelBooking(9);
        assertEquals(0, BigDecimal.ZERO.compareTo(r.refund()));
        assertEquals(1, r.segmentsCancelled());
    }

    @Test
    void placeOrderErrors() {
        jobReturns(fail(20003, "Insufficient stock for product 1"));
        assertThrows(Conflict.class, () -> service.placeOrder(new PlaceOrderRequest(7, null, null, List.of(new OrderLine(1, 1)))));
        jobReturns(fail(20002, "no customer"));
        assertThrows(NotFound.class, () -> service.placeOrder(new PlaceOrderRequest(7, null, null, List.of(new OrderLine(1, 1)))));
    }

    @Test
    void bookFlightMapsTheResultAndDefaultsTheChannel() throws Exception {
        jobReturns(ok("\"booking_id\":1000000007,\"booking_ref\":\"VNLMYO\",\"customer_id\":222,\"flight_id\":143,\"cabin\":\"BUSINESS\",\"seat_no\":\"3A\",\"fare\":4552.50,\"miles_earned\":8205"));
        BookFlightResult r = service.bookFlight(new BookFlightRequest(222, 143, "BUSINESS", "Ava Hassan", null));
        assertEquals("VNLMYO", r.bookingRef());
        assertEquals("3A", r.seatNo());
        assertEquals(new BigDecimal("4552.50"), r.fare());
        assertEquals(8205, r.milesEarned());
        Map<String, Object> sent = sentArguments();
        assertEquals("WEB", sent.get("channel"));
        assertEquals("Ava Hassan", sent.get("passenger"));
    }

    @Test
    void bookFlightErrorsNameTheFlight() {
        jobReturns(fail(20103, "no flight"));
        assertEquals("Flight 143 not found", assertThrows(NotFound.class, () -> service.bookFlight(new BookFlightRequest(1, 143, "FIRST", "A", null))).getMessage());
        jobReturns(fail(20107, "Cabin sold out"));
        assertThrows(Conflict.class, () -> service.bookFlight(new BookFlightRequest(1, 143, "FIRST", "A", null)));
    }

    @Test
    void cancelBooking() throws Exception {
        jobReturns(ok("\"booking_id\":5,\"refund\":900.00,\"segments_cancelled\":2,\"booking_status\":\"CANCELLED\""));
        CancelBookingResult r = service.cancelBooking(5);
        assertEquals(new BigDecimal("900.00"), r.refund());
        assertEquals(2, r.segmentsCancelled());
        assertEquals(Map.of("booking_id", 5), sentArguments());
        jobReturns(fail(20110, "no booking"));
        assertEquals("Booking 5 not found", assertThrows(NotFound.class, () -> service.cancelBooking(5)).getMessage());
        jobReturns(fail(20111, "already cancelled"));
        assertThrows(Conflict.class, () -> service.cancelBooking(5));
    }

    @Test
    void delayAndCancelFlight() throws Exception {
        jobReturns(ok("\"flight_id\":134,\"status\":\"DELAYED\",\"affected_bookings\":8"));
        assertEquals(new FlightOpResult(134, "DELAYED", 8L), service.delayFlight(134, new DelayRequest(300, "weather")));
        Map<String, Object> sent = sentArguments();
        assertEquals(false, sent.get("cancel"));
        assertEquals(300, sent.get("minutes"));

        jobReturns(ok("\"flight_id\":134,\"status\":\"CANCELLED\",\"affected_bookings\":5"));
        assertEquals("CANCELLED", service.cancelFlight(134, null).status());
        assertEquals("CANCELLED", service.cancelFlight(134, new CancelFlightRequest("crew")).status());

        jobReturns(fail(20121, "already operated"));
        assertThrows(Conflict.class, () -> service.delayFlight(134, new DelayRequest(10, null)));
    }

    @Test
    void hubAndTripsAndImpactStripTheReturnCode() throws Exception {
        jobReturns(ok("\"value\":[]"));
        assertFalse(service.customerHub(72).containsKey("rc"));
        assertFalse(service.trips(72).containsKey("rc"));
        jobReturns(fail(20002, "x"));
        assertThrows(NotFound.class, () -> service.customerHub(72));
        jobReturns(ok("\"passengers\":[]"));
        assertFalse(service.disruptionImpact(134).containsKey("rc"));
        assertEquals(Map.of("flight_id", 134), sentArguments());
    }

    @Test
    void processDisruptionsOmitsAsOfWhenNotGiven() throws Exception {
        jobReturns("[]");
        service.processDisruptions(180, null);
        assertEquals(Map.of("min_delay", 180), sentArguments());
        service.processDisruptions(360, 99L);
        assertEquals(Map.of("min_delay", 360, "as_of", 99), sentArguments());
    }

    @Test
    void clusterFailuresBecomeUnavailable() {
        when(compute.execute(any(JobTarget.class), any(JobDescriptor.class), anyString())).thenThrow(new RuntimeException("connection lost"));
        assertTrue(assertThrows(Unavailable.class, () -> service.customer360(7)).getMessage().contains("connection lost"));
    }

    @Test
    void anUnreadableResultBecomesUnavailable() {
        jobReturns("{not json");
        assertThrows(Unavailable.class, () -> service.customer360(7));
    }

    @Test
    void perCustomerJobsAreRoutedToTheCustomersNodeAndTheOthersAnywhere() throws Exception {
        jobReturns(ok(""));
        service.customer360(7);
        ArgumentCaptor<JobTarget> t = ArgumentCaptor.forClass(JobTarget.class);
        verify(compute).execute(t.capture(), any(JobDescriptor.class), anyString());
        assertTrue(t.getValue().getClass().getSimpleName().toLowerCase().contains("colocated"), t.getValue().getClass().getName());
    }
}
