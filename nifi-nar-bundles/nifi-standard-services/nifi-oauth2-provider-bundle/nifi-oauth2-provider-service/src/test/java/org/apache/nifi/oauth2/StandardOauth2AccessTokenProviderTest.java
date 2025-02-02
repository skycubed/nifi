/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.oauth2;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.Processor;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.util.NoOpProcessor;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class StandardOauth2AccessTokenProviderTest {
    private static final String AUTHORIZATION_SERVER_URL = "http://authorizationServerUrl";
    private static final String USERNAME = "username";
    private static final String PASSWORD = "password";
    private static final String CLIENT_ID = "clientId";
    private static final String CLIENT_SECRET = "clientSecret";

    private StandardOauth2AccessTokenProvider testSubject;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private OkHttpClient mockHttpClient;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ConfigurationContext mockContext;

    @Mock
    private ComponentLog mockLogger;
    @Captor
    private ArgumentCaptor<String> debugCaptor;
    @Captor
    private ArgumentCaptor<String> errorCaptor;
    @Captor
    private ArgumentCaptor<Throwable> throwableCaptor;

    @BeforeEach
    public void setUp() {
        testSubject = new StandardOauth2AccessTokenProvider() {
            @Override
            protected OkHttpClient createHttpClient(ConfigurationContext context) {
                return mockHttpClient;
            }

            @Override
            protected ComponentLog getLogger() {
                return mockLogger;
            }
        };

        when(mockContext.getProperty(StandardOauth2AccessTokenProvider.GRANT_TYPE).getValue()).thenReturn(StandardOauth2AccessTokenProvider.RESOURCE_OWNER_PASSWORD_CREDENTIALS_GRANT_TYPE.getValue());
        when(mockContext.getProperty(StandardOauth2AccessTokenProvider.AUTHORIZATION_SERVER_URL).evaluateAttributeExpressions().getValue()).thenReturn(AUTHORIZATION_SERVER_URL);
        when(mockContext.getProperty(StandardOauth2AccessTokenProvider.USERNAME).evaluateAttributeExpressions().getValue()).thenReturn(USERNAME);
        when(mockContext.getProperty(StandardOauth2AccessTokenProvider.PASSWORD).getValue()).thenReturn(PASSWORD);
        when(mockContext.getProperty(StandardOauth2AccessTokenProvider.CLIENT_ID).evaluateAttributeExpressions().getValue()).thenReturn(CLIENT_ID);
        when(mockContext.getProperty(StandardOauth2AccessTokenProvider.CLIENT_SECRET).getValue()).thenReturn(CLIENT_SECRET);

        testSubject.onEnabled(mockContext);
    }

    @Test
    public void testInvalidWhenClientCredentialsGrantTypeSetWithoutClientId() throws Exception {
        Processor processor = new NoOpProcessor();
        TestRunner runner = TestRunners.newTestRunner(processor);

        runner.addControllerService("testSubject", testSubject);

        runner.setProperty(testSubject, StandardOauth2AccessTokenProvider.AUTHORIZATION_SERVER_URL, "http://unimportant");

        runner.setProperty(testSubject, StandardOauth2AccessTokenProvider.GRANT_TYPE, StandardOauth2AccessTokenProvider.CLIENT_CREDENTIALS_GRANT_TYPE);

        runner.assertNotValid(testSubject);
    }

    @Test
    public void testValidWhenClientCredentialsGrantTypeSetWithClientId() throws Exception {
        Processor processor = new NoOpProcessor();
        TestRunner runner = TestRunners.newTestRunner(processor);

        runner.addControllerService("testSubject", testSubject);

        runner.setProperty(testSubject, StandardOauth2AccessTokenProvider.AUTHORIZATION_SERVER_URL, "http://unimportant");

        runner.setProperty(testSubject, StandardOauth2AccessTokenProvider.GRANT_TYPE, StandardOauth2AccessTokenProvider.CLIENT_CREDENTIALS_GRANT_TYPE);
        runner.setProperty(testSubject, StandardOauth2AccessTokenProvider.CLIENT_ID, "clientId");
        runner.setProperty(testSubject, StandardOauth2AccessTokenProvider.CLIENT_SECRET, "clientSecret");

        runner.assertValid(testSubject);
    }

    @Test
    public void testAcquireNewToken() throws Exception {
        String accessTokenValue = "access_token_value";

        Response response = buildResponse(
            200,
            "{ \"access_token\":\"" + accessTokenValue + "\" }"
        );

        when(mockHttpClient.newCall(any(Request.class)).execute()).thenReturn(response);

        String actual = testSubject.getAccessDetails().getAccessToken();

        assertEquals(accessTokenValue, actual);
    }

    @Test
    public void testRefreshToken() throws Exception {
        String firstToken = "first_token";
        String expectedToken = "second_token";

        Response response1 = buildResponse(
            200,
            "{ \"access_token\":\"" + firstToken + "\", \"expires_in\":\"-60\", \"refresh_token\":\"not_checking_in_this_test\" }"
        );

        Response response2 = buildResponse(
            200,
            "{ \"access_token\":\"" + expectedToken + "\" }"
        );

        when(mockHttpClient.newCall(any(Request.class)).execute()).thenReturn(response1, response2);

        testSubject.getAccessDetails();
        String actualToken = testSubject.getAccessDetails().getAccessToken();

        assertEquals(expectedToken, actualToken);
    }

    @Test
    public void testIOExceptionDuringRefreshAndSubsequentAcquire() throws Exception {
        String refreshErrorMessage = "refresh_error";
        String acquireErrorMessage = "acquire_error";

        AtomicInteger callCounter = new AtomicInteger(0);
        when(mockHttpClient.newCall(any(Request.class)).execute()).thenAnswer(invocation -> {
            callCounter.incrementAndGet();

            if (callCounter.get() == 1) {
                return buildSuccessfulInitResponse();
            } else if (callCounter.get() == 2) {
                throw new IOException(refreshErrorMessage);
            } else if (callCounter.get() == 3) {
                throw new IOException(acquireErrorMessage);
            }

            throw new IllegalStateException("Test improperly defined mock HTTP responses.");
        });

        testSubject.getAccessDetails();
        UncheckedIOException actualException = assertThrows(
            UncheckedIOException.class,
            () -> testSubject.getAccessDetails()
        );

        checkLoggedDebugWhenRefreshFails();

        checkLoggedRefreshError(new UncheckedIOException("OAuth2 access token request failed", new IOException(refreshErrorMessage)));

        checkError(new UncheckedIOException("OAuth2 access token request failed", new IOException(acquireErrorMessage)), actualException);
    }

    @Test
    public void testIOExceptionDuringRefreshSuccessfulSubsequentAcquire() throws Exception {
        String refreshErrorMessage = "refresh_error";
        String expectedToken = "expected_token";

        Response successfulAcquireResponse = buildResponse(
            200,
            "{ \"access_token\":\"" + expectedToken + "\", \"expires_in\":\"0\", \"refresh_token\":\"not_checking_in_this_test\" }"
        );

        AtomicInteger callCounter = new AtomicInteger(0);
        when(mockHttpClient.newCall(any(Request.class)).execute()).thenAnswer(invocation -> {
            callCounter.incrementAndGet();

            if (callCounter.get() == 1) {
                return buildSuccessfulInitResponse();
            } else if (callCounter.get() == 2) {
                throw new IOException(refreshErrorMessage);
            } else if (callCounter.get() == 3) {
                return successfulAcquireResponse;
            }

            throw new IllegalStateException("Test improperly defined mock HTTP responses.");
        });

        testSubject.getAccessDetails();
        String actualToken = testSubject.getAccessDetails().getAccessToken();

        checkLoggedDebugWhenRefreshFails();

        checkLoggedRefreshError(new UncheckedIOException("OAuth2 access token request failed", new IOException(refreshErrorMessage)));

        assertEquals(expectedToken, actualToken);
    }

    @Test
    public void testHTTPErrorDuringRefreshAndSubsequentAcquire() throws Exception {
        String errorRefreshResponseBody = "{ \"error_response\":\"refresh_error\" }";
        String errorAcquireResponseBody = "{ \"error_response\":\"acquire_error\" }";

        Response errorRefreshResponse = buildResponse(500, errorRefreshResponseBody);
        Response errorAcquireResponse = buildResponse(503, errorAcquireResponseBody);

        AtomicInteger callCounter = new AtomicInteger(0);
        when(mockHttpClient.newCall(any(Request.class)).execute()).thenAnswer(invocation -> {
            callCounter.incrementAndGet();

            if (callCounter.get() == 1) {
                return buildSuccessfulInitResponse();
            } else if (callCounter.get() == 2) {
                return errorRefreshResponse;
            } else if (callCounter.get() == 3) {
                return errorAcquireResponse;
            }

            throw new IllegalStateException("Test improperly defined mock HTTP responses.");
        });

        List<String> expectedLoggedError = Arrays.asList(
            String.format("OAuth2 access token request failed [HTTP %d], response:%n%s", 500, errorRefreshResponseBody),
            String.format("OAuth2 access token request failed [HTTP %d], response:%n%s", 503, errorAcquireResponseBody)
        );

        testSubject.getAccessDetails();
        ProcessException actualException = assertThrows(
            ProcessException.class,
            () -> testSubject.getAccessDetails()
        );

        checkLoggedDebugWhenRefreshFails();

        checkLoggedRefreshError(new ProcessException("OAuth2 access token request failed [HTTP 500]"));

        checkedLoggedErrorWhenRefreshReturnsBadHTTPResponse(expectedLoggedError);

        checkError(new ProcessException("OAuth2 access token request failed [HTTP 503]"), actualException);
    }

    @Test
    public void testHTTPErrorDuringRefreshSuccessfulSubsequentAcquire() throws Exception {
        String expectedRefreshErrorResponse = "{ \"error_response\":\"refresh_error\" }";
        String expectedToken = "expected_token";

        Response errorRefreshResponse = buildResponse(500, expectedRefreshErrorResponse);
        Response successfulAcquireResponse = buildResponse(
            200,
            "{ \"access_token\":\"" + expectedToken + "\", \"expires_in\":\"0\", \"refresh_token\":\"not_checking_in_this_test\" }"
        );

        AtomicInteger callCounter = new AtomicInteger(0);
        when(mockHttpClient.newCall(any(Request.class)).execute()).thenAnswer(invocation -> {
            callCounter.incrementAndGet();

            if (callCounter.get() == 1) {
                return buildSuccessfulInitResponse();
            } else if (callCounter.get() == 2) {
                return errorRefreshResponse;
            } else if (callCounter.get() == 3) {
                return successfulAcquireResponse;
            }

            throw new IllegalStateException("Test improperly defined mock HTTP responses.");
        });

        List<String> expectedLoggedError = Collections.singletonList(String.format("OAuth2 access token request failed [HTTP %d], response:%n%s", 500, expectedRefreshErrorResponse));

        testSubject.getAccessDetails();
        String actualToken = testSubject.getAccessDetails().getAccessToken();

        checkLoggedDebugWhenRefreshFails();

        checkLoggedRefreshError(new ProcessException("OAuth2 access token request failed [HTTP 500]"));

        checkedLoggedErrorWhenRefreshReturnsBadHTTPResponse(expectedLoggedError);

        assertEquals(expectedToken, actualToken);
    }

    private Response buildSuccessfulInitResponse() {
        return buildResponse(
            200,
            "{ \"access_token\":\"exists_but_value_irrelevant\", \"expires_in\":\"-60\", \"refresh_token\":\"not_checking_in_this_test\" }"
        );
    }

    private Response buildResponse(int code, String body) {
        return new Response.Builder()
            .request(new Request.Builder()
                .url("http://unimportant_but_required")
                .build()
            )
            .protocol(Protocol.HTTP_2)
            .message("unimportant_but_required")
            .code(code)
            .body(ResponseBody.create(
                body.getBytes(),
                MediaType.parse("application/json"))
            )
            .build();
    }

    private void checkLoggedDebugWhenRefreshFails() {
        verify(mockLogger, times(3)).debug(debugCaptor.capture());
        List<String> actualDebugMessages = debugCaptor.getAllValues();

        assertEquals(
            Arrays.asList("Getting a new access token", "Refreshing access token", "Getting a new access token"),
            actualDebugMessages
        );
    }

    private void checkedLoggedErrorWhenRefreshReturnsBadHTTPResponse(List<String> expectedLoggedError) {
        verify(mockLogger, times(expectedLoggedError.size())).error(errorCaptor.capture());
        List<String> actualLoggedError = errorCaptor.getAllValues();

        assertEquals(expectedLoggedError, actualLoggedError);
    }

    private void checkLoggedRefreshError(Throwable expectedRefreshError) {
        verify(mockLogger).info(eq("Couldn't refresh access token"), throwableCaptor.capture());
        Throwable actualRefreshError = throwableCaptor.getValue();

        checkError(expectedRefreshError, actualRefreshError);
    }

    private void checkError(Throwable expectedError, Throwable actualError) {
        assertEquals(expectedError.getClass(), actualError.getClass());
        assertEquals(expectedError.getMessage(), actualError.getMessage());
        if (expectedError.getCause() != null || actualError.getCause() != null) {
            assertEquals(expectedError.getCause().getClass(), actualError.getCause().getClass());
            assertEquals(expectedError.getCause().getMessage(), actualError.getCause().getMessage());
        }
    }
}
