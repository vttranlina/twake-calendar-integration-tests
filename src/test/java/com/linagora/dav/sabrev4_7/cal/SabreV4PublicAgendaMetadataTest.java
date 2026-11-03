/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.dav.sabrev4_7.cal;

import static com.linagora.dav.CalendarAssert.assertThatCalendar;
import static io.restassured.RestAssured.given;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_NO_CONTENT;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linagora.dav.DockerTwakeCalendarExtensionV4_7;
import com.linagora.dav.DockerTwakeCalendarSetup.DockerService;
import com.linagora.dav.OpenPaasUser;

import io.restassured.RestAssured;

class SabreV4PublicAgendaMetadataTest {
    private static final String METADATA = """
        X-PUBLICLY-CREATED;VALUE=BOOLEAN:TRUE
        X-PUBLICLY-CREATOR:booker@example.com
        X-OPENPAAS-BOOKING-LINK:server-booking-link
        """;

    @RegisterExtension
    static DockerTwakeCalendarExtensionV4_7 extension = new DockerTwakeCalendarExtensionV4_7();

    private static OpenPaasUser user;
    private String uid;
    private String path;

    @BeforeAll
    static void provisionLdapUser() {
        // Random users from newTestUser() exist in Mongo and the side service,
        // but only this fixture also has real LDAP credentials for ordinary DAV Basic auth.
        user = extension.newTestUser("ldap_user1");
        extension.twakeCalendarProvisioningService().createUserInUsersRepository(user.email()).block();
    }

    @BeforeEach
    void setUp() {
        RestAssured.reset();
        uid = UUID.randomUUID().toString();
        path = "/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics";
        RestAssured.requestSpecification = extension.davRequestSpecification()
            .contentType("text/calendar; charset=utf-8")
            .accept("text/calendar");
    }

    @ParameterizedTest
    @MethodSource("clientWrites")
    void clientCannotCreateBookingMetadata(String contentType, String query, boolean json) {
        // Given a regular user's authenticated client, including the import and jCal paths.
        String body = json ? jcal() : ics(METADATA);

        // When the client attempts to create a booking by writing server metadata.
        given()
            .auth().preemptive().basic(user.email(), user.password())
            .contentType(contentType)
            .body(body)
        .when()
            .put(path + query)
        .then()
            .statusCode(SC_CREATED);

        // Then the event is saved without the forged metadata; ordinary X-properties survive.
        assertThatCalendar(read()).isEqualTo(ics(""));
    }

    static Stream<Arguments> clientWrites() {
        return Stream.of(
            Arguments.of("text/calendar", "", false),
            Arguments.of("text/calendar", "?import", false),
            Arguments.of("application/calendar+json", "", true));
    }

    @ParameterizedTest
    @MethodSource("clientUpdates")
    void clientCannotAddChangeOrDeleteStoredMetadata(String metadata) {
        // Given metadata created by the same verified admin impersonation used by booking routes.
        given().header("Authorization", user.impersonatedBasicAuth()).body(ics(METADATA))
            .when().put(path).then().statusCode(SC_CREATED);
        String stored = read();

        // When the regular client changes content while altering, adding or omitting metadata.
        given()
            .auth().preemptive().basic(user.email(), user.password())
            .body(ics(metadata).replace("SUMMARY:Booking", "SUMMARY:Edited by client"))
        .when()
            .put(path)
        .then()
            .statusCode(SC_NO_CONTENT);

        // Then server metadata is preserved while the ordinary edit succeeds.
        assertThatCalendar(read()).isEqualTo(stored.replace("SUMMARY:Booking", "SUMMARY:Edited by client"));
    }

    static Stream<String> clientUpdates() {
        return Stream.of("", METADATA, """
            X-PUBLICLY-CREATED;VALUE=BOOLEAN:FALSE
            X-PUBLICLY-CREATOR:attacker@example.com
            X-PUBLICLY-CREATOR:other@example.com
            X-PUBLICLY-DELETED;VALUE=BOOLEAN:TRUE
            X-PUBLICLY-CANCELLED-BY:attacker@example.com
            X-OPENPAAS-BOOKING-LINK:forged-link
            """);
    }

    @Test
    void verifiedServerCanCreateAndCancelBooking() {
        // Given a booking written by side-service admin impersonation.
        given().header("Authorization", user.impersonatedBasicAuth()).body(ics(METADATA))
            .when().put(path).then().statusCode(SC_CREATED);
        assertThat(read()).contains("X-PUBLICLY-CREATOR:booker@example.com");

        // When the cancellation flow updates STATUS and the cancelling identity.
        String cancelled = ics(METADATA + "STATUS:CANCELLED\nX-PUBLICLY-CANCELLED-BY:booker@example.com\n");
        given().header("Authorization", user.impersonatedBasicAuth()).body(cancelled)
            .when().put(path).then().statusCode(SC_NO_CONTENT);

        // Then the trusted update is stored, and technical-token writes can manage metadata too.
        assertThatCalendar(read()).isEqualTo(cancelled);
        given()
            .header("TwakeCalendarToken", extension.twakeCalendarProvisioningService().generateToken())
            .body(ics(""))
        .when().put(path).then().statusCode(SC_NO_CONTENT);
        assertThatCalendar(read()).isEqualTo(ics(""));
    }

    @Test
    void userJwtCannotWriteServerMetadata() {
        // Given a valid user JWT, authenticated independently from admin impersonation.
        String token = given()
            .baseUri(extension.getDockerTwakeCalendarSetupSingleton().getServiceUri(DockerService.CALENDAR_SIDE, "http").toString())
            .auth().preemptive().basic(user.email(), user.password())
        .when().post("/api/jwt/generate")
        .then().statusCode(SC_OK).extract().asString();
        token = token.substring(1, token.length() - 1);

        // When the user attempts to create booking metadata.
        given().header("Authorization", "Bearer " + token).body(ics(METADATA))
            .when().put(path).then().statusCode(SC_CREATED);

        // Then the JWT grants user access without server-write privilege.
        assertThatCalendar(read()).isEqualTo(ics(""));
    }

    @Test
    void clientItipSubmissionCannotForgeBookingMetadata() {
        // Given an ordinary invitation already stored in the recipient's calendar.
        String participants = "ORGANIZER:mailto:organizer@example.com\nATTENDEE:mailto:" + user.email() + "\n";
        given().auth().preemptive().basic(user.email(), user.password()).body(ics(participants))
            .when().put(path).then().statusCode(SC_CREATED);

        // When the recipient relays an iTIP update carrying forged booking metadata.
        String update = ics(participants + METADATA + "SEQUENCE:1\n")
            .replace("SUMMARY:Booking", "SUMMARY:Updated invitation");
        given()
            .auth().preemptive().basic(user.email(), user.password())
            .contentType("application/json")
            .body(Map.of("uid", uid, "method", "REQUEST", "sender", "organizer@example.com",
                "recipient", user.email(), "sequence", 1, "ical", update))
        .when().post("/itip").then().statusCode(SC_NO_CONTENT);

        // Then the alternative write path keeps the event ordinary.
        assertThat(read()).contains("SUMMARY:Updated invitation")
            .doesNotContain("X-PUBLICLY-", "X-OPENPAAS-BOOKING-LINK");
    }

    @Test
    void clientCannotCopyBookingOrEraseItByCopyOrMove() {
        // Given an ordinary event and a server-authored booking.
        String ordinaryPath = path.replace(".ics", "-ordinary.ics");
        given().header("Authorization", user.impersonatedBasicAuth()).body(ics(METADATA))
            .when().put(path).then().statusCode(SC_CREATED);
        given().auth().preemptive().basic(user.email(), user.password()).body(ics(""))
            .when().put(ordinaryPath).then().statusCode(SC_CREATED);

        // When the client attempts to duplicate or overwrite booking metadata through DAV operations.
        given().auth().preemptive().basic(user.email(), user.password())
            .header("Destination", path.replace(".ics", "-copy.ics"))
            .when().request("COPY", path).then().statusCode(SC_FORBIDDEN)
            .body(containsString("Public agenda metadata"));
        for (String method : new String[] {"COPY", "MOVE"}) {
            given().auth().preemptive().basic(user.email(), user.password())
                .header("Destination", path).header("Overwrite", "T")
                .when().request(method, ordinaryPath).then().statusCode(SC_FORBIDDEN)
                .body(containsString("Public agenda metadata"));
        }

        // Then filing the booking in another calendar still retains its server metadata.
        // Sabre forbids in-place CalendarObject renames, so exercise its cross-calendar MOVE.
        String calendarPath = "/calendars/" + user.id() + "/moved-" + uid;
        given().auth().preemptive().basic(user.email(), user.password())
            .contentType("application/xml")
            .body("""
                <c:mkcalendar xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:d="DAV:">
                  <d:set><d:prop><d:displayname>Moved bookings</d:displayname></d:prop></d:set>
                </c:mkcalendar>
                """)
            .when().request("MKCALENDAR", calendarPath).then().statusCode(SC_CREATED);
        String moved = calendarPath + "/" + uid + ".ics";
        given().auth().preemptive().basic(user.email(), user.password())
            .header("Destination", moved)
            .when().request("MOVE", path).then().statusCode(SC_CREATED);
        path = moved;
        assertThatCalendar(read()).isEqualTo(ics(METADATA));
    }

    private String read() {
        return given().auth().preemptive().basic(user.email(), user.password())
            .when().get(path).then().statusCode(SC_OK).extract().asString();
    }

    private String ics(String metadata) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Metadata test//EN
            BEGIN:VEVENT
            UID:{uid}
            DTSTAMP:20261103T000000Z
            DTSTART:20301103T090000Z
            DTEND:20301103T100000Z
            SUMMARY:Booking
            X-CLIENT-NOTE;VALUE=TEXT:Keep me
            {metadata}END:VEVENT
            END:VCALENDAR
            """.replace("{uid}", uid).replace("{metadata}", metadata).replace("\n", "\r\n");
    }

    private String jcal() {
        return """
            ["vcalendar", [["version", {}, "text", "2.0"], ["prodid", {}, "text", "-//Metadata test//EN"]], [
              ["vevent", [
                ["uid", {}, "text", "{uid}"], ["dtstamp", {}, "date-time", "2026-11-03T00:00:00Z"],
                ["dtstart", {}, "date-time", "2030-11-03T09:00:00Z"], ["dtend", {}, "date-time", "2030-11-03T10:00:00Z"],
                ["summary", {}, "text", "Booking"], ["x-client-note", {}, "text", "Keep me"],
                ["x-publicly-created", {}, "boolean", true], ["x-publicly-creator", {}, "text", "booker@example.com"],
                ["x-openpaas-booking-link", {}, "text", "server-booking-link"]
              ], []]
            ]]
            """.replace("{uid}", uid);
    }
}
