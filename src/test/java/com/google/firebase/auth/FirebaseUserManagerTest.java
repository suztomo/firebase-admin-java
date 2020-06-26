/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.firebase.auth;

import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.api.client.googleapis.util.Utils;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.json.GenericJson;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.TestOnlyImplFirebaseTrampolines;
import com.google.firebase.auth.FirebaseUserManager.EmailLinkType;
import com.google.firebase.auth.multitenancy.ListTenantsPage;
import com.google.firebase.auth.multitenancy.Tenant;
import com.google.firebase.auth.multitenancy.TenantAwareFirebaseAuth;
import com.google.firebase.auth.multitenancy.TenantManager;
import com.google.firebase.internal.SdkUtils;
import com.google.firebase.testing.MultiRequestMockHttpTransport;
import com.google.firebase.testing.TestResponseInterceptor;
import com.google.firebase.testing.TestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

public class FirebaseUserManagerTest {

  private static final JsonFactory JSON_FACTORY = Utils.getDefaultJsonFactory();
  private static final String TEST_TOKEN = "token";
  private static final GoogleCredentials credentials = new MockGoogleCredentials(TEST_TOKEN);

  private static final ActionCodeSettings ACTION_CODE_SETTINGS = ActionCodeSettings.builder()
          .setUrl("https://example.dynamic.link")
          .setHandleCodeInApp(true)
          .setDynamicLinkDomain("custom.page.link")
          .setIosBundleId("com.example.ios")
          .setAndroidPackageName("com.example.android")
          .setAndroidInstallApp(true)
          .setAndroidMinimumVersion("6")
          .build();
  private static final Map<String, Object> ACTION_CODE_SETTINGS_MAP =
          ACTION_CODE_SETTINGS.getProperties();

  private static final String PROJECT_BASE_URL =
      "https://identitytoolkit.googleapis.com/v2/projects/test-project-id";
  private static final String TENANTS_BASE_URL = PROJECT_BASE_URL + "/tenants";

  @After
  public void tearDown() {
    TestOnlyImplFirebaseTrampolines.clearInstancesForTest();
  }

  @Test
  public void testProjectIdRequired() {
    FirebaseApp.initializeApp(new FirebaseOptions.Builder()
            .setCredentials(credentials)
            .build());
    FirebaseAuth auth = FirebaseAuth.getInstance();
    try {
      auth.getUserManager();
      fail("No error thrown for missing project ID");
    } catch (IllegalArgumentException expected) {
      assertEquals(
          "Project ID is required to access the auth service. Use a service account credential "
              + "or set the project ID explicitly via FirebaseOptions. Alternatively you can "
              + "also set the project ID via the GOOGLE_CLOUD_PROJECT environment variable.",
          expected.getMessage());
    }
  }

  @Test
  public void testGetUser() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("getUser.json"));
    UserRecord userRecord = FirebaseAuth.getInstance().getUserAsync("testuser").get();
    checkUserRecord(userRecord);
    checkRequestHeaders(interceptor);
  }

  @Test
  public void testGetUserWithNotFoundError() throws Exception {
    initializeAppForUserManagement(TestUtils.loadResource("getUserError.json"));
    try {
      FirebaseAuth.getInstance().getUserAsync("testuser").get();
      fail("No error thrown for invalid response");
    } catch (ExecutionException e) {
      assertThat(e.getCause(), instanceOf(FirebaseAuthException.class));
      FirebaseAuthException authException = (FirebaseAuthException) e.getCause();
      assertEquals(FirebaseUserManager.USER_NOT_FOUND_ERROR, authException.getErrorCode());
    }
  }

  @Test
  public void testGetUserByEmail() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("getUser.json"));
    UserRecord userRecord = FirebaseAuth.getInstance()
        .getUserByEmailAsync("testuser@example.com").get();
    checkUserRecord(userRecord);
    checkRequestHeaders(interceptor);
  }

  @Test
  public void testGetUserByEmailWithNotFoundError() throws Exception {
    initializeAppForUserManagement(TestUtils.loadResource("getUserError.json"));
    try {
      FirebaseAuth.getInstance().getUserByEmailAsync("testuser@example.com").get();
      fail("No error thrown for invalid response");
    } catch (ExecutionException e) {
      assertThat(e.getCause(), instanceOf(FirebaseAuthException.class));
      FirebaseAuthException authException = (FirebaseAuthException) e.getCause();
      assertEquals(FirebaseUserManager.USER_NOT_FOUND_ERROR, authException.getErrorCode());
    }
  }

  @Test
  public void testGetUserByPhoneNumber() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("getUser.json"));
    UserRecord userRecord = FirebaseAuth.getInstance()
        .getUserByPhoneNumberAsync("+1234567890").get();
    checkUserRecord(userRecord);
    checkRequestHeaders(interceptor);
  }

  @Test
  public void testGetUserByPhoneNumberWithNotFoundError() throws Exception {
    initializeAppForUserManagement(TestUtils.loadResource("getUserError.json"));
    try {
      FirebaseAuth.getInstance().getUserByPhoneNumberAsync("+1234567890").get();
      fail("No error thrown for invalid response");
    } catch (ExecutionException e) {
      assertThat(e.getCause(), instanceOf(FirebaseAuthException.class));
      FirebaseAuthException authException = (FirebaseAuthException) e.getCause();
      assertEquals(FirebaseUserManager.USER_NOT_FOUND_ERROR, authException.getErrorCode());
    }
  }

  @Test
  public void testGetUsersExceeds100() throws Exception {
    FirebaseApp.initializeApp(new FirebaseOptions.Builder()
            .setCredentials(credentials)
            .build());
    List<UserIdentifier> identifiers = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      identifiers.add(new UidIdentifier("uid_" + i));
    }

    try {
      FirebaseAuth.getInstance().getUsers(identifiers);
      fail("No error thrown for too many supplied identifiers");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void testGetUsersNull() throws Exception {
    FirebaseApp.initializeApp(new FirebaseOptions.Builder()
            .setCredentials(credentials)
            .build());
    try {
      FirebaseAuth.getInstance().getUsers(null);
      fail("No error thrown for null identifiers");
    } catch (NullPointerException expected) {
      // expected
    }
  }

  @Test
  public void testGetUsersEmpty() throws Exception {
    initializeAppForUserManagement();
    GetUsersResult result = FirebaseAuth.getInstance().getUsers(new ArrayList<UserIdentifier>());
    assertTrue(result.getUsers().isEmpty());
    assertTrue(result.getNotFound().isEmpty());
  }

  @Test
  public void testGetUsersAllNonExisting() throws Exception {
    initializeAppForUserManagement("{ \"users\": [] }");
    List<UserIdentifier> ids = ImmutableList.<UserIdentifier>of(
        new UidIdentifier("id-that-doesnt-exist"));
    GetUsersResult result = FirebaseAuth.getInstance().getUsers(ids);
    assertTrue(result.getUsers().isEmpty());
    assertEquals(ids.size(), result.getNotFound().size());
    assertTrue(result.getNotFound().containsAll(ids));
  }

  @Test
  public void testGetUsersMultipleIdentifierTypes() throws Exception {
    initializeAppForUserManagement((""
        + "{ "
        + "    'users': [{ "
        + "        'localId': 'uid1', "
        + "        'email': 'user1@example.com', "
        + "        'phoneNumber': '+15555550001' "
        + "    }, { "
        + "        'localId': 'uid2', "
        + "        'email': 'user2@example.com', "
        + "        'phoneNumber': '+15555550002' "
        + "    }, { "
        + "        'localId': 'uid3', "
        + "        'email': 'user3@example.com', "
        + "        'phoneNumber': '+15555550003' "
        + "    }, { "
        + "        'localId': 'uid4', "
        + "        'email': 'user4@example.com', "
        + "        'phoneNumber': '+15555550004', "
        + "        'providerUserInfo': [{ "
        + "            'providerId': 'google.com', "
        + "            'rawId': 'google_uid4' "
        + "        }] "
        + "    }] "
        + "} "
        ).replace("'", "\""));

    UidIdentifier doesntExist = new UidIdentifier("this-uid-doesnt-exist");
    List<UserIdentifier> ids = ImmutableList.<UserIdentifier>of(
        new UidIdentifier("uid1"),
        new EmailIdentifier("user2@example.com"),
        new PhoneIdentifier("+15555550003"),
        new ProviderIdentifier("google.com", "google_uid4"),
        doesntExist);
    GetUsersResult result = FirebaseAuth.getInstance().getUsers(ids);
    Collection<String> uids = userRecordsToUids(result.getUsers());
    assertTrue(uids.containsAll(ImmutableList.of("uid1", "uid2", "uid3", "uid4")));
    assertEquals(1, result.getNotFound().size());
    assertTrue(result.getNotFound().contains(doesntExist));
  }

  private Collection<String> userRecordsToUids(Collection<UserRecord> userRecords) {
    Collection<String> uids = new HashSet<>();
    for (UserRecord userRecord : userRecords) {
      uids.add(userRecord.getUid());
    }
    return uids;
  }

  @Test
  public void testInvalidUidIdentifier() throws Exception {
    try {
      new UidIdentifier("too long " + Strings.repeat(".", 128));
      fail("No error thrown for invalid uid");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void testInvalidEmailIdentifier() throws Exception {
    try {
      new EmailIdentifier("invalid email addr");
      fail("No error thrown for invalid email");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void testInvalidPhoneIdentifier() throws Exception {
    try {
      new PhoneIdentifier("invalid phone number");
      fail("No error thrown for invalid phone number");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void testInvalidProviderIdentifier() throws Exception {
    try {
      new ProviderIdentifier("", "valid-uid");
      fail("No error thrown for invalid provider id");
    } catch (IllegalArgumentException expected) {
      // expected
    }

    try {
      new ProviderIdentifier("valid-id", "");
      fail("No error thrown for invalid provider uid");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void testListUsers() throws Exception {
    final TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("listUsers.json"));
    ListUsersPage page = FirebaseAuth.getInstance().listUsersAsync(null, 999).get();
    assertEquals(2, Iterables.size(page.getValues()));
    for (ExportedUserRecord userRecord : page.getValues()) {
      checkUserRecord(userRecord);
      assertEquals("passwordHash", userRecord.getPasswordHash());
      assertEquals("passwordSalt", userRecord.getPasswordSalt());
    }
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);

    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals(999, url.getFirst("maxResults"));
    assertNull(url.getFirst("nextPageToken"));
  }

  @Test
  public void testListUsersWithPageToken() throws Exception {
    final TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("listUsers.json"));
    ListUsersPage page = FirebaseAuth.getInstance().listUsersAsync("token", 999).get();
    assertEquals(2, Iterables.size(page.getValues()));
    for (ExportedUserRecord userRecord : page.getValues()) {
      checkUserRecord(userRecord);
      assertEquals("passwordHash", userRecord.getPasswordHash());
      assertEquals("passwordSalt", userRecord.getPasswordSalt());
    }
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);

    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals(999, url.getFirst("maxResults"));
    assertEquals("token", url.getFirst("nextPageToken"));
  }

  @Test
  public void testListZeroUsers() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement("{}");
    ListUsersPage page = FirebaseAuth.getInstance().listUsersAsync(null).get();
    assertTrue(Iterables.isEmpty(page.getValues()));
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);
  }

  @Test
  public void testCreateUser() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("createUser.json"),
        TestUtils.loadResource("getUser.json"));
    UserRecord user =
        FirebaseAuth.getInstance().createUserAsync(new UserRecord.CreateRequest()).get();
    checkUserRecord(user);
    checkRequestHeaders(interceptor);
  }

  @Test
  public void testUpdateUser() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("createUser.json"),
        TestUtils.loadResource("getUser.json"));
    UserRecord user = FirebaseAuth.getInstance()
        .updateUserAsync(new UserRecord.UpdateRequest("testuser")).get();
    checkUserRecord(user);
    checkRequestHeaders(interceptor);
  }

  @Test
  public void testSetCustomAttributes() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("createUser.json"));
    // should not throw
    ImmutableMap<String, Object> claims = ImmutableMap.<String, Object>of(
        "admin", true, "package", "gold");
    FirebaseAuth.getInstance().setCustomUserClaimsAsync("testuser", claims).get();
    checkRequestHeaders(interceptor);

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals("testuser", parsed.get("localId"));
    assertEquals(JSON_FACTORY.toString(claims), parsed.get("customAttributes"));
  }

  @Test
  public void testRevokeRefreshTokens() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("createUser.json"));
    // should not throw
    FirebaseAuth.getInstance().revokeRefreshTokensAsync("testuser").get();
    checkRequestHeaders(interceptor);

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals("testuser", parsed.get("localId"));
    assertNotNull(parsed.get("validSince"));
  }

  @Test
  public void testDeleteUser() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("deleteUser.json"));
    // should not throw
    FirebaseAuth.getInstance().deleteUserAsync("testuser").get();
    checkRequestHeaders(interceptor);
  }

  @Test
  public void testDeleteUsersExceeds1000() throws Exception {
    FirebaseApp.initializeApp(new FirebaseOptions.Builder()
            .setCredentials(credentials)
            .build());
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 1001; i++) {
      ids.add("id" + i);
    }
    try {
      FirebaseAuth.getInstance().deleteUsersAsync(ids);
      fail("No error thrown for too many uids");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void testDeleteUsersInvalidId() throws Exception {
    FirebaseApp.initializeApp(new FirebaseOptions.Builder()
            .setCredentials(credentials)
            .build());
    try {
      FirebaseAuth.getInstance().deleteUsersAsync(
          ImmutableList.of("too long " + Strings.repeat(".", 128)));
      fail("No error thrown for too long uid");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void testDeleteUsersIndexesErrorsCorrectly() throws Exception {
    initializeAppForUserManagement((""
        + "{ "
        + "    'errors': [{ "
        + "        'index': 0, "
        + "        'localId': 'uid1', "
        + "        'message': 'NOT_DISABLED : Disable the account before batch deletion.' "
        + "    }, { "
        + "        'index': 2, "
        + "        'localId': 'uid3', "
        + "        'message': 'something awful' "
        + "    }] "
        + "} "
        ).replace("'", "\""));

    DeleteUsersResult result = FirebaseAuth.getInstance().deleteUsersAsync(ImmutableList.of(
          "uid1", "uid2", "uid3", "uid4"
          )).get();

    assertEquals(2, result.getSuccessCount());
    assertEquals(2, result.getFailureCount());
    assertEquals(2, result.getErrors().size());
    assertEquals(0, result.getErrors().get(0).getIndex());
    assertEquals(
        "NOT_DISABLED : Disable the account before batch deletion.",
        result.getErrors().get(0).getReason());
    assertEquals(2, result.getErrors().get(1).getIndex());
    assertEquals("something awful", result.getErrors().get(1).getReason());
  }

  @Test
  public void testDeleteUsersSuccess() throws Exception {
    initializeAppForUserManagement("{}");

    DeleteUsersResult result = FirebaseAuth.getInstance().deleteUsersAsync(ImmutableList.of(
          "uid1", "uid2", "uid3"
          )).get();

    assertEquals(3, result.getSuccessCount());
    assertEquals(0, result.getFailureCount());
    assertTrue(result.getErrors().isEmpty());
  }

  @Test
  public void testImportUsers() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement("{}");
    ImportUserRecord user1 = ImportUserRecord.builder().setUid("user1").build();
    ImportUserRecord user2 = ImportUserRecord.builder().setUid("user2").build();

    List<ImportUserRecord> users = ImmutableList.of(user1, user2);
    UserImportResult result = FirebaseAuth.getInstance().importUsersAsync(users, null).get();
    checkRequestHeaders(interceptor);
    assertEquals(2, result.getSuccessCount());
    assertEquals(0, result.getFailureCount());
    assertTrue(result.getErrors().isEmpty());

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals(1, parsed.size());
    List<Map<String, Object>> expected = ImmutableList.of(
        user1.getProperties(JSON_FACTORY),
        user2.getProperties(JSON_FACTORY)
    );
    assertEquals(expected, parsed.get("users"));
  }

  @Test
  public void testImportUsersError() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("importUsersError.json"));
    ImportUserRecord user1 = ImportUserRecord.builder()
        .setUid("user1")
        .build();
    ImportUserRecord user2 = ImportUserRecord.builder()
        .setUid("user2")
        .build();
    ImportUserRecord user3 = ImportUserRecord.builder()
        .setUid("user3")
        .build();

    List<ImportUserRecord> users = ImmutableList.of(user1, user2, user3);
    UserImportResult result = FirebaseAuth.getInstance().importUsersAsync(users, null).get();
    checkRequestHeaders(interceptor);
    assertEquals(1, result.getSuccessCount());
    assertEquals(2, result.getFailureCount());
    assertEquals(2, result.getErrors().size());

    ErrorInfo error = result.getErrors().get(0);
    assertEquals(0, error.getIndex());
    assertEquals("Some error occurred in user1", error.getReason());
    error = result.getErrors().get(1);
    assertEquals(2, error.getIndex());
    assertEquals("Another error occurred in user3", error.getReason());

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals(1, parsed.size());
    List<Map<String, Object>> expected = ImmutableList.of(
        user1.getProperties(JSON_FACTORY),
        user2.getProperties(JSON_FACTORY),
        user3.getProperties(JSON_FACTORY)
    );
    assertEquals(expected, parsed.get("users"));
  }

  @Test
  public void testImportUsersWithHash() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement("{}");
    ImportUserRecord user1 = ImportUserRecord.builder()
        .setUid("user1")
        .build();
    ImportUserRecord user2 = ImportUserRecord.builder()
        .setUid("user2")
        .setPasswordHash("password".getBytes())
        .build();

    List<ImportUserRecord> users = ImmutableList.of(user1, user2);
    UserImportHash hash = new UserImportHash("MOCK_HASH") {
      @Override
      protected Map<String, Object> getOptions() {
        return ImmutableMap.<String, Object>of("key1", "value1", "key2", true);
      }
    };
    UserImportResult result = FirebaseAuth.getInstance().importUsersAsync(users,
        UserImportOptions.withHash(hash)).get();
    checkRequestHeaders(interceptor);
    assertEquals(2, result.getSuccessCount());
    assertEquals(0, result.getFailureCount());
    assertTrue(result.getErrors().isEmpty());

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals(4, parsed.size());
    List<Map<String, Object>> expected = ImmutableList.of(
        user1.getProperties(JSON_FACTORY),
        user2.getProperties(JSON_FACTORY)
    );
    assertEquals(expected, parsed.get("users"));
    assertEquals("MOCK_HASH", parsed.get("hashAlgorithm"));
    assertEquals("value1", parsed.get("key1"));
    assertEquals(Boolean.TRUE, parsed.get("key2"));
  }

  @Test
  public void testImportUsersMissingHash() {
    initializeAppForUserManagement();
    ImportUserRecord user1 = ImportUserRecord.builder()
        .setUid("user1")
        .build();
    ImportUserRecord user2 = ImportUserRecord.builder()
        .setUid("user2")
        .setPasswordHash("password".getBytes())
        .build();

    List<ImportUserRecord> users = ImmutableList.of(user1, user2);
    try {
      FirebaseAuth.getInstance().importUsersAsync(users);
      fail("No error thrown for missing hash option");
    } catch (IllegalArgumentException expected) {
      assertEquals("UserImportHash option is required when at least one user has a password. "
          + "Provide a UserImportHash via UserImportOptions.withHash().", expected.getMessage());
    }
  }

  @Test
  public void testImportUsersEmptyList() {
    initializeAppForUserManagement();
    try {
      FirebaseAuth.getInstance().importUsersAsync(ImmutableList.<ImportUserRecord>of());
      fail("No error thrown for empty user list");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void testImportUsersLargeList() {
    initializeAppForUserManagement();
    ImmutableList.Builder<ImportUserRecord> users = ImmutableList.builder();
    for (int i = 0; i < 1001; i++) {
      users.add(ImportUserRecord.builder().setUid("test" + i).build());
    }
    try {
      FirebaseAuth.getInstance().importUsersAsync(users.build());
      fail("No error thrown for large list");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void testGetTenant() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("tenant.json"));

    Tenant tenant = FirebaseAuth.getInstance().getTenantManager().getTenant("TENANT_1");

    checkTenant(tenant, "TENANT_1");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "GET", TENANTS_BASE_URL + "/TENANT_1");
  }

  @Test
  public void testGetTenantWithNotFoundError() throws Exception {
    TestResponseInterceptor interceptor =
        initializeAppForUserManagementWithStatusCode(404,
            "{\"error\": {\"message\": \"TENANT_NOT_FOUND\"}}");
    try {
      FirebaseAuth.getInstance().getTenantManager().getTenant("UNKNOWN");
      fail("No error thrown for invalid response");
    } catch (FirebaseAuthException e) {
      assertEquals(FirebaseUserManager.TENANT_NOT_FOUND_ERROR, e.getErrorCode());
    }
    checkUrl(interceptor, "GET", TENANTS_BASE_URL + "/UNKNOWN");
  }

  @Test
  public void testListTenants() throws Exception {
    final TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("listTenants.json"));

    ListTenantsPage page = FirebaseAuth.getInstance().getTenantManager().listTenants(null, 999);

    ImmutableList<Tenant> tenants = ImmutableList.copyOf(page.getValues());
    assertEquals(2, tenants.size());
    checkTenant(tenants.get(0), "TENANT_1");
    checkTenant(tenants.get(1), "TENANT_2");
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "GET", TENANTS_BASE_URL);
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals(999, url.getFirst("pageSize"));
    assertNull(url.getFirst("pageToken"));
  }

  @Test
  public void testListTenantsWithPageToken() throws Exception {
    final TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("listTenants.json"));

    ListTenantsPage page = FirebaseAuth.getInstance().getTenantManager().listTenants("token", 999);

    ImmutableList<Tenant> tenants = ImmutableList.copyOf(page.getValues());
    assertEquals(2, tenants.size());
    checkTenant(tenants.get(0), "TENANT_1");
    checkTenant(tenants.get(1), "TENANT_2");
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "GET", TENANTS_BASE_URL);
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals(999, url.getFirst("pageSize"));
    assertEquals("token", url.getFirst("pageToken"));
  }

  @Test
  public void testListZeroTenants() throws Exception {
    final TestResponseInterceptor interceptor = initializeAppForUserManagement("{}");

    ListTenantsPage page = FirebaseAuth.getInstance().getTenantManager().listTenants(null);

    assertTrue(Iterables.isEmpty(page.getValues()));
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);
  }

  @Test
  public void testCreateTenant() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("tenant.json"));
    Tenant.CreateRequest request = new Tenant.CreateRequest()
        .setDisplayName("DISPLAY_NAME")
        .setPasswordSignInAllowed(true)
        .setEmailLinkSignInEnabled(false);

    Tenant tenant = FirebaseAuth.getInstance().getTenantManager().createTenant(request);

    checkTenant(tenant, "TENANT_1");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "POST", TENANTS_BASE_URL);
    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals("DISPLAY_NAME", parsed.get("displayName"));
    assertEquals(true, parsed.get("allowPasswordSignup"));
    assertEquals(false, parsed.get("enableEmailLinkSignin"));
  }

  @Test
  public void testCreateTenantMinimal() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("tenant.json"));
    Tenant.CreateRequest request = new Tenant.CreateRequest();

    Tenant tenant = FirebaseAuth.getInstance().getTenantManager().createTenant(request);

    checkTenant(tenant, "TENANT_1");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "POST", TENANTS_BASE_URL);
    GenericJson parsed = parseRequestContent(interceptor);
    assertNull(parsed.get("displayName"));
    assertNull(parsed.get("allowPasswordSignup"));
    assertNull(parsed.get("enableEmailLinkSignin"));
  }

  @Test
  public void testCreateTenantError() throws Exception {
    TestResponseInterceptor interceptor =
        initializeAppForUserManagementWithStatusCode(404,
            "{\"error\": {\"message\": \"INTERNAL_ERROR\"}}");
    try {
      FirebaseAuth.getInstance().getTenantManager().createTenant(new Tenant.CreateRequest());
      fail("No error thrown for invalid response");
    } catch (FirebaseAuthException e) {
      assertEquals(FirebaseUserManager.INTERNAL_ERROR, e.getErrorCode());
    }
    checkUrl(interceptor, "POST", TENANTS_BASE_URL);
  }

  @Test
  public void testUpdateTenant() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("tenant.json"));
    Tenant.UpdateRequest request = new Tenant.UpdateRequest("TENANT_1")
        .setDisplayName("DISPLAY_NAME")
        .setPasswordSignInAllowed(true)
        .setEmailLinkSignInEnabled(false);

    Tenant tenant = FirebaseAuth.getInstance().getTenantManager().updateTenant(request);

    checkTenant(tenant, "TENANT_1");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "PATCH", TENANTS_BASE_URL + "/TENANT_1");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals("allowPasswordSignup,displayName,enableEmailLinkSignin",
        url.getFirst("updateMask"));
    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals("DISPLAY_NAME", parsed.get("displayName"));
    assertEquals(true, parsed.get("allowPasswordSignup"));
    assertEquals(false, parsed.get("enableEmailLinkSignin"));
  }

  @Test
  public void testUpdateTenantMinimal() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("tenant.json"));
    Tenant.UpdateRequest request =
        new Tenant.UpdateRequest("TENANT_1").setDisplayName("DISPLAY_NAME");

    Tenant tenant = FirebaseAuth.getInstance().getTenantManager().updateTenant(request);

    checkTenant(tenant, "TENANT_1");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "PATCH", TENANTS_BASE_URL + "/TENANT_1");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals("displayName", url.getFirst("updateMask"));
    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals("DISPLAY_NAME", parsed.get("displayName"));
    assertNull(parsed.get("allowPasswordSignup"));
    assertNull(parsed.get("enableEmailLinkSignin"));
  }

  @Test
  public void testUpdateTenantNoValues() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("tenant.json"));
    TenantManager tenantManager = FirebaseAuth.getInstance().getTenantManager();
    try {
      tenantManager.updateTenant(new Tenant.UpdateRequest("TENANT_1"));
      fail("No error thrown for empty tenant update");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }

  @Test
  public void testUpdateTenantError() throws Exception {
    TestResponseInterceptor interceptor =
        initializeAppForUserManagementWithStatusCode(404,
            "{\"error\": {\"message\": \"INTERNAL_ERROR\"}}");
    Tenant.UpdateRequest request =
        new Tenant.UpdateRequest("TENANT_1").setDisplayName("DISPLAY_NAME");
    try {
      FirebaseAuth.getInstance().getTenantManager().updateTenant(request);
      fail("No error thrown for invalid response");
    } catch (FirebaseAuthException e) {
      assertEquals(FirebaseUserManager.INTERNAL_ERROR, e.getErrorCode());
    }
    checkUrl(interceptor, "PATCH", TENANTS_BASE_URL + "/TENANT_1");
  }

  @Test
  public void testDeleteTenant() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement("{}");

    FirebaseAuth.getInstance().getTenantManager().deleteTenant("TENANT_1");

    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "DELETE", TENANTS_BASE_URL + "/TENANT_1");
  }

  @Test
  public void testDeleteTenantWithNotFoundError() throws Exception {
    TestResponseInterceptor interceptor =
        initializeAppForUserManagementWithStatusCode(404,
            "{\"error\": {\"message\": \"TENANT_NOT_FOUND\"}}");
    try {
      FirebaseAuth.getInstance().getTenantManager().deleteTenant("UNKNOWN");
      fail("No error thrown for invalid response");
    } catch (FirebaseAuthException e) {
      assertEquals(FirebaseUserManager.TENANT_NOT_FOUND_ERROR, e.getErrorCode());
    }
    checkUrl(interceptor, "DELETE", TENANTS_BASE_URL + "/UNKNOWN");
  }

  @Test
  public void testCreateSessionCookie() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("createSessionCookie.json"));
    SessionCookieOptions options = SessionCookieOptions.builder()
        .setExpiresIn(TimeUnit.HOURS.toMillis(1))
        .build();
    String cookie = FirebaseAuth.getInstance().createSessionCookieAsync("testToken", options).get();
    assertEquals("MockCookieString", cookie);
    checkRequestHeaders(interceptor);

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals(2, parsed.size());
    assertEquals("testToken", parsed.get("idToken"));
    assertEquals(new BigDecimal(3600), parsed.get("validDuration"));
  }

  @Test
  public void testCreateSessionCookieInvalidArguments() {
    initializeAppForUserManagement();
    SessionCookieOptions options = SessionCookieOptions.builder()
        .setExpiresIn(TimeUnit.HOURS.toMillis(1))
        .build();
    try {
      FirebaseAuth.getInstance().createSessionCookieAsync(null, options);
      fail("No error thrown for null id token");
    } catch (IllegalArgumentException expected) {
      // expected
    }

    try {
      FirebaseAuth.getInstance().createSessionCookieAsync("", options);
      fail("No error thrown for empty id token");
    } catch (IllegalArgumentException expected) {
      // expected
    }

    try {
      FirebaseAuth.getInstance().createSessionCookieAsync("idToken", null);
      fail("No error thrown for null options");
    } catch (NullPointerException expected) {
      // expected
    }
  }

  @Test
  public void testInvalidSessionCookieOptions() {
    try {
      SessionCookieOptions.builder().build();
      fail("No error thrown for unspecified expiresIn");
    } catch (IllegalArgumentException expected) {
      // expected
    }

    try {
      SessionCookieOptions.builder().setExpiresIn(TimeUnit.SECONDS.toMillis(299)).build();
      fail("No error thrown for low expiresIn");
    } catch (IllegalArgumentException expected) {
      // expected
    }

    try {
      SessionCookieOptions.builder().setExpiresIn(TimeUnit.DAYS.toMillis(14) + 1).build();
      fail("No error thrown for high expiresIn");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void testGetUserHttpError() throws Exception {
    List<UserManagerOp> operations = ImmutableList.<UserManagerOp>builder()
        .add(new UserManagerOp() {
          @Override
          public void call(FirebaseAuth auth) throws Exception {
            auth.getUserAsync("testuser").get();
          }
        })
        .add(new UserManagerOp() {
          @Override
          public void call(FirebaseAuth auth) throws Exception {
            auth.getUserByEmailAsync("testuser@example.com").get();
          }
        })
        .add(new UserManagerOp() {
          @Override
          public void call(FirebaseAuth auth) throws Exception {
            auth.getUserByPhoneNumberAsync("+1234567890").get();
          }
        })
        .add(new UserManagerOp() {
          @Override
          public void call(FirebaseAuth auth) throws Exception {
            auth.createUserAsync(new UserRecord.CreateRequest()).get();
          }
        })
        .add(new UserManagerOp() {
          @Override
          public void call(FirebaseAuth auth) throws Exception {
            auth.updateUserAsync(new UserRecord.UpdateRequest("test")).get();
          }
        })
        .add(new UserManagerOp() {
          @Override
          public void call(FirebaseAuth auth) throws Exception {
            auth.deleteUserAsync("testuser").get();
          }
        })
        .add(new UserManagerOp() {
          @Override
          public void call(FirebaseAuth auth) throws Exception {
            auth.listUsersAsync(null, 1000).get();
          }
        })
        .build();

    MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
    FirebaseAuth auth = getRetryDisabledAuth(response);

    // Test for common HTTP error codes
    for (int code : ImmutableList.of(302, 400, 401, 404, 500)) {
      for (UserManagerOp operation : operations) {
        // Need to reset these every iteration
        response.setContent("{}");
        response.setStatusCode(code);
        try {
          operation.call(auth);
          fail("No error thrown for HTTP error: " + code);
        } catch (ExecutionException e) {
          assertThat(e.getCause(), instanceOf(FirebaseAuthException.class));
          FirebaseAuthException authException = (FirebaseAuthException) e.getCause();
          String msg = String.format("Unexpected HTTP response with status: %d; body: {}", code);
          assertEquals(msg, authException.getMessage());
          assertThat(authException.getCause(), instanceOf(HttpResponseException.class));
          assertEquals(FirebaseUserManager.INTERNAL_ERROR, authException.getErrorCode());
        }
      }
    }

    // Test error payload parsing
    for (UserManagerOp operation : operations) {
      response.setContent("{\"error\": {\"message\": \"USER_NOT_FOUND\"}}");
      response.setStatusCode(500);
      try {
        operation.call(auth);
        fail("No error thrown for HTTP error");
      }  catch (ExecutionException e) {
        assertThat(e.getCause().toString(), e.getCause(), instanceOf(FirebaseAuthException.class));
        FirebaseAuthException authException = (FirebaseAuthException) e.getCause();
        assertEquals("User management service responded with an error", authException.getMessage());
        assertThat(authException.getCause(), instanceOf(HttpResponseException.class));
        assertEquals(FirebaseUserManager.USER_NOT_FOUND_ERROR, authException.getErrorCode());
      }
    }
  }

  @Test
  public void testGetUserMalformedJsonError() throws Exception {
    initializeAppForUserManagement("{\"not\" json}");
    try {
      FirebaseAuth.getInstance().getUserAsync("testuser").get();
      fail("No error thrown for JSON error");
    }  catch (ExecutionException e) {
      assertThat(e.getCause(), instanceOf(FirebaseAuthException.class));
      FirebaseAuthException authException = (FirebaseAuthException) e.getCause();
      assertThat(authException.getCause(), instanceOf(IOException.class));
      assertEquals(FirebaseUserManager.INTERNAL_ERROR, authException.getErrorCode());
    }
  }

  @Test
  public void testGetUserUnexpectedHttpError() throws Exception {
    MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
    response.setContent("{\"not\" json}");
    response.setStatusCode(500);
    FirebaseAuth auth = getRetryDisabledAuth(response);
    try {
      auth.getUserAsync("testuser").get();
      fail("No error thrown for JSON error");
    }  catch (ExecutionException e) {
      assertThat(e.getCause(), instanceOf(FirebaseAuthException.class));
      FirebaseAuthException authException = (FirebaseAuthException) e.getCause();
      assertThat(authException.getCause(), instanceOf(HttpResponseException.class));
      assertEquals("Unexpected HTTP response with status: 500; body: {\"not\" json}",
          authException.getMessage());
      assertEquals(FirebaseUserManager.INTERNAL_ERROR, authException.getErrorCode());
    }
  }

  @Test
  public void testTimeout() throws Exception {
    MockHttpTransport transport = new MultiRequestMockHttpTransport(ImmutableList.of(
        new MockLowLevelHttpResponse().setContent(TestUtils.loadResource("getUser.json"))));
    FirebaseApp.initializeApp(new FirebaseOptions.Builder()
        .setCredentials(credentials)
        .setProjectId("test-project-id")
        .setHttpTransport(transport)
        .setConnectTimeout(30000)
        .setReadTimeout(60000)
        .build());
    FirebaseAuth auth = FirebaseAuth.getInstance();
    FirebaseUserManager userManager = auth.getUserManager();
    TestResponseInterceptor interceptor = new TestResponseInterceptor();
    userManager.setInterceptor(interceptor);

    FirebaseAuth.getInstance().getUserAsync("testuser").get();
    HttpRequest request = interceptor.getResponse().getRequest();
    assertEquals(30000, request.getConnectTimeout());
    assertEquals(60000, request.getReadTimeout());
  }

  @Test
  public void testUserBuilder() {
    Map<String, Object> map = new UserRecord.CreateRequest().getProperties();
    assertTrue(map.isEmpty());
  }

  @Test
  public void testUserBuilderWithParams() {
    Map<String, Object> map = new UserRecord.CreateRequest()
        .setUid("TestUid")
        .setDisplayName("Display Name")
        .setPhotoUrl("http://test.com/example.png")
        .setEmail("test@example.com")
        .setPhoneNumber("+1234567890")
        .setEmailVerified(true)
        .setPassword("secret")
        .getProperties();
    assertEquals(7, map.size());
    assertEquals("TestUid", map.get("localId"));
    assertEquals("Display Name", map.get("displayName"));
    assertEquals("http://test.com/example.png", map.get("photoUrl"));
    assertEquals("test@example.com", map.get("email"));
    assertEquals("+1234567890", map.get("phoneNumber"));
    assertTrue((Boolean) map.get("emailVerified"));
    assertEquals("secret", map.get("password"));
  }

  @Test
  public void testInvalidUid() {
    UserRecord.CreateRequest user = new UserRecord.CreateRequest();
    try {
      user.setUid(null);
      fail("No error thrown for null uid");
    } catch (Exception ignore) {
      // expected
    }

    try {
      user.setUid("");
      fail("No error thrown for empty uid");
    } catch (Exception ignore) {
      // expected
    }

    try {
      user.setUid(String.format("%0129d", 0));
      fail("No error thrown for long uid");
    } catch (Exception ignore) {
      // expected
    }
  }

  @Test
  public void testInvalidDisplayName() {
    UserRecord.CreateRequest user = new UserRecord.CreateRequest();
    try {
      user.setDisplayName(null);
      fail("No error thrown for null display name");
    } catch (Exception ignore) {
      // expected
    }
  }

  @Test
  public void testInvalidPhotoUrl() {
    UserRecord.CreateRequest user = new UserRecord.CreateRequest();
    try {
      user.setPhotoUrl(null);
      fail("No error thrown for null photo url");
    } catch (Exception ignore) {
      // expected
    }

    try {
      user.setPhotoUrl("");
      fail("No error thrown for invalid photo url");
    } catch (Exception ignore) {
      // expected
    }

    try {
      user.setPhotoUrl("not-a-url");
      fail("No error thrown for invalid photo url");
    } catch (Exception ignore) {
      // expected
    }
  }

  @Test
  public void testInvalidEmail() {
    UserRecord.CreateRequest user = new UserRecord.CreateRequest();
    try {
      user.setEmail(null);
      fail("No error thrown for null email");
    } catch (Exception ignore) {
      // expected
    }

    try {
      user.setEmail("");
      fail("No error thrown for invalid email");
    } catch (Exception ignore) {
      // expected
    }

    try {
      user.setEmail("not-an-email");
      fail("No error thrown for invalid email");
    } catch (Exception ignore) {
      // expected
    }
  }

  @Test
  public void testInvalidPhoneNumber() {
    UserRecord.CreateRequest user = new UserRecord.CreateRequest();
    try {
      user.setPhoneNumber(null);
      fail("No error thrown for null phone number");
    } catch (Exception ignore) {
      // expected
    }

    try {
      user.setPhoneNumber("");
      fail("No error thrown for invalid phone number");
    } catch (Exception ignore) {
      // expected
    }

    try {
      user.setPhoneNumber("not-a-phone");
      fail("No error thrown for invalid phone number");
    } catch (Exception ignore) {
      // expected
    }
  }

  @Test
  public void testInvalidPassword() {
    UserRecord.CreateRequest user = new UserRecord.CreateRequest();
    try {
      user.setPassword(null);
      fail("No error thrown for null password");
    } catch (Exception ignore) {
      // expected
    }

    try {
      user.setPassword("aaaaa");
      fail("No error thrown for short password");
    } catch (Exception ignore) {
      // expected
    }
  }

  @Test
  public void testUserUpdater() throws IOException {
    UserRecord.UpdateRequest update = new UserRecord.UpdateRequest("test");
    Map<String, Object> claims = ImmutableMap.<String, Object>of("admin", true, "package", "gold");
    Map<String, Object> map = update
        .setDisplayName("Display Name")
        .setPhotoUrl("http://test.com/example.png")
        .setEmail("test@example.com")
        .setPhoneNumber("+1234567890")
        .setEmailVerified(true)
        .setPassword("secret")
        .setCustomClaims(claims)
        .getProperties(JSON_FACTORY);
    assertEquals(8, map.size());
    assertEquals(update.getUid(), map.get("localId"));
    assertEquals("Display Name", map.get("displayName"));
    assertEquals("http://test.com/example.png", map.get("photoUrl"));
    assertEquals("test@example.com", map.get("email"));
    assertEquals("+1234567890", map.get("phoneNumber"));
    assertTrue((Boolean) map.get("emailVerified"));
    assertEquals("secret", map.get("password"));
    assertEquals(JSON_FACTORY.toString(claims), map.get("customAttributes"));
  }

  @Test
  public void testNullJsonFactory() {
    UserRecord.UpdateRequest update = new UserRecord.UpdateRequest("test");
    Map<String, Object> claims = ImmutableMap.<String, Object>of("admin", true, "package", "gold");
    update.setCustomClaims(claims);
    try {
      update.getProperties(null);
      fail("No error thrown for null JsonFactory");
    } catch (NullPointerException ignore) {
      // expected
    }
  }

  @Test
  public void testNullCustomClaims() {
    UserRecord.UpdateRequest update = new UserRecord.UpdateRequest("test");
    Map<String, Object> map = update
        .setCustomClaims(null)
        .getProperties(JSON_FACTORY);
    assertEquals(2, map.size());
    assertEquals(update.getUid(), map.get("localId"));
    assertEquals("{}", map.get("customAttributes"));
  }

  @Test
  public void testEmptyCustomClaims() {
    UserRecord.UpdateRequest update = new UserRecord.UpdateRequest("test");
    Map<String, Object> map = update
        .setCustomClaims(ImmutableMap.<String, Object>of())
        .getProperties(JSON_FACTORY);
    assertEquals(2, map.size());
    assertEquals(update.getUid(), map.get("localId"));
    assertEquals("{}", map.get("customAttributes"));
  }

  @Test
  public void testDeleteDisplayName() {
    Map<String, Object> map = new UserRecord.UpdateRequest("test")
        .setDisplayName(null)
        .getProperties(JSON_FACTORY);
    assertEquals(ImmutableList.of("DISPLAY_NAME"), map.get("deleteAttribute"));
  }

  @Test
  public void testDeletePhotoUrl() {
    Map<String, Object> map = new UserRecord.UpdateRequest("test")
        .setPhotoUrl(null)
        .getProperties(JSON_FACTORY);
    assertEquals(ImmutableList.of("PHOTO_URL"), map.get("deleteAttribute"));
  }

  @Test
  public void testDeletePhoneNumber() {
    Map<String, Object> map = new UserRecord.UpdateRequest("test")
        .setPhoneNumber(null)
        .getProperties(JSON_FACTORY);
    assertEquals(ImmutableList.of("phone"), map.get("deleteProvider"));
  }

  @Test
  public void testInvalidUpdatePhotoUrl() {
    UserRecord.UpdateRequest update = new UserRecord.UpdateRequest("test");
    try {
      update.setPhotoUrl("");
      fail("No error thrown for invalid photo url");
    } catch (Exception ignore) {
      // expected
    }

    try {
      update.setPhotoUrl("not-a-url");
      fail("No error thrown for invalid photo url");
    } catch (Exception ignore) {
      // expected
    }
  }

  @Test
  public void testInvalidUpdateEmail() {
    UserRecord.UpdateRequest update = new UserRecord.UpdateRequest("test");
    try {
      update.setEmail(null);
      fail("No error thrown for null email");
    } catch (Exception ignore) {
      // expected
    }

    try {
      update.setEmail("");
      fail("No error thrown for invalid email");
    } catch (Exception ignore) {
      // expected
    }

    try {
      update.setEmail("not-an-email");
      fail("No error thrown for invalid email");
    } catch (Exception ignore) {
      // expected
    }
  }

  @Test
  public void testInvalidUpdatePhoneNumber() {
    UserRecord.UpdateRequest update = new UserRecord.UpdateRequest("test");

    try {
      update.setPhoneNumber("");
      fail("No error thrown for invalid phone number");
    } catch (Exception ignore) {
      // expected
    }

    try {
      update.setPhoneNumber("not-a-phone");
      fail("No error thrown for invalid phone number");
    } catch (Exception ignore) {
      // expected
    }
  }

  @Test
  public void testInvalidUpdatePassword() {
    UserRecord.UpdateRequest update = new UserRecord.UpdateRequest("test");
    try {
      update.setPassword(null);
      fail("No error thrown for null password");
    } catch (Exception ignore) {
      // expected
    }

    try {
      update.setPassword("aaaaa");
      fail("No error thrown for short password");
    } catch (Exception ignore) {
      // expected
    }
  }

  @Test
  public void testInvalidCustomClaims() {
    UserRecord.UpdateRequest update = new UserRecord.UpdateRequest("test");
    for (String claim : FirebaseUserManager.RESERVED_CLAIMS) {
      try {
        update.setCustomClaims(ImmutableMap.<String, Object>of(claim, "value"));
        fail("No error thrown for reserved claim");
      } catch (Exception ignore) {
        // expected
      }
    }
  }

  @Test
  public void testLargeCustomClaims() {
    final StringBuilder builder = new StringBuilder();
    for (int i = 0; i < 1001; i++) {
      builder.append("a");
    }
    UserRecord.UpdateRequest update = new UserRecord.UpdateRequest("test");
    update.setCustomClaims(ImmutableMap.<String, Object>of("key", builder.toString()));
    try {
      update.getProperties(JSON_FACTORY);
      fail("No error thrown for large claims payload");
    } catch (Exception ignore) {
      // expected
    }
  }

  @Test
  public void testGeneratePasswordResetLinkNoEmail() throws Exception {
    initializeAppForUserManagement();
    try {
      FirebaseAuth.getInstance().generatePasswordResetLinkAsync(null).get();
      fail("No error thrown for null email");
    } catch (IllegalArgumentException expected) {
    }

    try {
      FirebaseAuth.getInstance().generatePasswordResetLinkAsync("").get();
      fail("No error thrown for empty email");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testGeneratePasswordResetLinkWithSettings() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
            TestUtils.loadResource("generateEmailLink.json"));
    String link = FirebaseAuth.getInstance()
            .generatePasswordResetLinkAsync("test@example.com", ACTION_CODE_SETTINGS).get();
    assertEquals("https://mock-oob-link.for.auth.tests", link);
    checkRequestHeaders(interceptor);

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals(3 + ACTION_CODE_SETTINGS_MAP.size(), parsed.size());
    assertEquals("test@example.com", parsed.get("email"));
    assertEquals("PASSWORD_RESET", parsed.get("requestType"));
    assertTrue((Boolean) parsed.get("returnOobLink"));
    for (Map.Entry<String, Object> entry : ACTION_CODE_SETTINGS_MAP.entrySet()) {
      assertEquals(entry.getValue(), parsed.get(entry.getKey()));
    }
  }

  @Test
  public void testGeneratePasswordResetLink() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
            TestUtils.loadResource("generateEmailLink.json"));
    String link = FirebaseAuth.getInstance()
            .generatePasswordResetLinkAsync("test@example.com").get();
    assertEquals("https://mock-oob-link.for.auth.tests", link);
    checkRequestHeaders(interceptor);

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals(3, parsed.size());
    assertEquals("test@example.com", parsed.get("email"));
    assertEquals("PASSWORD_RESET", parsed.get("requestType"));
    assertTrue((Boolean) parsed.get("returnOobLink"));
  }

  @Test
  public void testGenerateEmailVerificationLinkNoEmail() throws Exception {
    initializeAppForUserManagement();
    try {
      FirebaseAuth.getInstance().generateEmailVerificationLinkAsync(null).get();
      fail("No error thrown for null email");
    } catch (IllegalArgumentException expected) {
    }

    try {
      FirebaseAuth.getInstance().generateEmailVerificationLinkAsync("").get();
      fail("No error thrown for empty email");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testGenerateEmailVerificationLinkWithSettings() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("generateEmailLink.json"));
    String link = FirebaseAuth.getInstance()
        .generateEmailVerificationLinkAsync("test@example.com", ACTION_CODE_SETTINGS).get();
    assertEquals("https://mock-oob-link.for.auth.tests", link);
    checkRequestHeaders(interceptor);

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals(3 + ACTION_CODE_SETTINGS_MAP.size(), parsed.size());
    assertEquals("test@example.com", parsed.get("email"));
    assertEquals("VERIFY_EMAIL", parsed.get("requestType"));
    assertTrue((Boolean) parsed.get("returnOobLink"));
    for (Map.Entry<String, Object> entry : ACTION_CODE_SETTINGS_MAP.entrySet()) {
      assertEquals(entry.getValue(), parsed.get(entry.getKey()));
    }
  }

  @Test
  public void testGenerateEmailVerificationLink() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("generateEmailLink.json"));
    String link = FirebaseAuth.getInstance()
        .generateEmailVerificationLinkAsync("test@example.com").get();
    assertEquals("https://mock-oob-link.for.auth.tests", link);
    checkRequestHeaders(interceptor);

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals(3, parsed.size());
    assertEquals("test@example.com", parsed.get("email"));
    assertEquals("VERIFY_EMAIL", parsed.get("requestType"));
    assertTrue((Boolean) parsed.get("returnOobLink"));
  }

  @Test
  public void testGenerateESignInWithEmailLinkNoEmail() throws Exception {
    initializeAppForUserManagement();
    try {
      FirebaseAuth.getInstance().generateSignInWithEmailLinkAsync(
          null, ACTION_CODE_SETTINGS).get();
      fail("No error thrown for null email");
    } catch (IllegalArgumentException expected) {
    }

    try {
      FirebaseAuth.getInstance().generateSignInWithEmailLinkAsync(
          "", ACTION_CODE_SETTINGS).get();
      fail("No error thrown for empty email");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testGenerateESignInWithEmailLinkNullSettings() throws Exception {
    initializeAppForUserManagement();
    try {
      FirebaseAuth.getInstance().generateSignInWithEmailLinkAsync(
          "test@example.com", null).get();
      fail("No error thrown for null email");
    } catch (NullPointerException expected) {
    }
  }

  @Test
  public void testGenerateSignInWithEmailLinkWithSettings() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("generateEmailLink.json"));
    String link = FirebaseAuth.getInstance()
        .generateSignInWithEmailLinkAsync("test@example.com", ACTION_CODE_SETTINGS).get();
    assertEquals("https://mock-oob-link.for.auth.tests", link);
    checkRequestHeaders(interceptor);

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals(3 + ACTION_CODE_SETTINGS_MAP.size(), parsed.size());
    assertEquals("test@example.com", parsed.get("email"));
    assertEquals("EMAIL_SIGNIN", parsed.get("requestType"));
    assertTrue((Boolean) parsed.get("returnOobLink"));
    for (Map.Entry<String, Object> entry : ACTION_CODE_SETTINGS_MAP.entrySet()) {
      assertEquals(entry.getValue(), parsed.get(entry.getKey()));
    }
  }

  @Test
  public void testHttpErrorWithCode() {
    MockLowLevelHttpResponse response = new MockLowLevelHttpResponse()
        .setContent("{\"error\": {\"message\": \"UNAUTHORIZED_DOMAIN\"}}")
        .setStatusCode(500);
    FirebaseAuth auth = getRetryDisabledAuth(response);
    FirebaseUserManager userManager = auth.getUserManager();
    try {
      userManager.getEmailActionLink(EmailLinkType.PASSWORD_RESET, "test@example.com", null);
      fail("No exception thrown for HTTP error");
    } catch (FirebaseAuthException e) {
      assertEquals("unauthorized-continue-uri", e.getErrorCode());
      assertThat(e.getCause(), instanceOf(HttpResponseException.class));
    }
  }

  @Test
  public void testUnexpectedHttpError() {
    MockLowLevelHttpResponse response = new MockLowLevelHttpResponse()
        .setContent("{}")
        .setStatusCode(500);
    FirebaseAuth auth = getRetryDisabledAuth(response);
    FirebaseUserManager userManager = auth.getUserManager();
    try {
      userManager.getEmailActionLink(EmailLinkType.PASSWORD_RESET, "test@example.com", null);
      fail("No exception thrown for HTTP error");
    } catch (FirebaseAuthException e) {
      assertEquals("internal-error", e.getErrorCode());
      assertThat(e.getCause(), instanceOf(HttpResponseException.class));
    }
  }

  @Test
  public void testCreateOidcProvider() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("oidc.json"));
    OidcProviderConfig.CreateRequest createRequest =
        new OidcProviderConfig.CreateRequest()
            .setProviderId("oidc.provider-id")
            .setDisplayName("DISPLAY_NAME")
            .setEnabled(true)
            .setClientId("CLIENT_ID")
            .setIssuer("https://oidc.com/issuer");

    OidcProviderConfig config = FirebaseAuth.getInstance().createOidcProviderConfig(createRequest);

    checkOidcProviderConfig(config, "oidc.provider-id");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "POST", PROJECT_BASE_URL + "/oauthIdpConfigs");
    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals("DISPLAY_NAME", parsed.get("displayName"));
    assertTrue((boolean) parsed.get("enabled"));
    assertEquals("CLIENT_ID", parsed.get("clientId"));
    assertEquals("https://oidc.com/issuer", parsed.get("issuer"));
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals("oidc.provider-id", url.getFirst("oauthIdpConfigId"));
  }

  @Test
  public void testCreateOidcProviderMinimal() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("oidc.json"));
    // Only the 'enabled' and 'displayName' fields can be omitted from an OIDC provider config
    // creation request.
    OidcProviderConfig.CreateRequest createRequest =
        new OidcProviderConfig.CreateRequest()
            .setProviderId("oidc.provider-id")
            .setClientId("CLIENT_ID")
            .setIssuer("https://oidc.com/issuer");

    FirebaseAuth.getInstance().createOidcProviderConfig(createRequest);
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "POST", PROJECT_BASE_URL + "/oauthIdpConfigs");
    GenericJson parsed = parseRequestContent(interceptor);
    assertNull(parsed.get("displayName"));
    assertNull(parsed.get("enabled"));
    assertEquals("CLIENT_ID", parsed.get("clientId"));
    assertEquals("https://oidc.com/issuer", parsed.get("issuer"));
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals("oidc.provider-id", url.getFirst("oauthIdpConfigId"));
  }

  @Test
  public void testCreateOidcProviderError() throws Exception {
    TestResponseInterceptor interceptor =
        initializeAppForUserManagementWithStatusCode(404,
            "{\"error\": {\"message\": \"INTERNAL_ERROR\"}}");
    OidcProviderConfig.CreateRequest createRequest =
        new OidcProviderConfig.CreateRequest().setProviderId("oidc.provider-id");
    try {
      FirebaseAuth.getInstance().createOidcProviderConfig(createRequest);
      fail("No error thrown for invalid response");
    } catch (FirebaseAuthException e) {
      assertEquals(FirebaseUserManager.INTERNAL_ERROR, e.getErrorCode());
    }
    checkUrl(interceptor, "POST", PROJECT_BASE_URL + "/oauthIdpConfigs");
  }

  @Test
  public void testCreateOidcProviderMissingId() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("oidc.json"));
    OidcProviderConfig.CreateRequest createRequest =
        new OidcProviderConfig.CreateRequest()
            .setDisplayName("DISPLAY_NAME")
            .setEnabled(true)
            .setClientId("CLIENT_ID")
            .setIssuer("https://oidc.com/issuer");
    try {
      FirebaseAuth.getInstance().createOidcProviderConfig(createRequest);
      fail("No error thrown for invalid response");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  @Test
  public void testTenantAwareCreateOidcProvider() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForTenantAwareUserManagement(
        "TENANT_ID",
        TestUtils.loadResource("oidc.json"));
    OidcProviderConfig.CreateRequest createRequest =
        new OidcProviderConfig.CreateRequest()
            .setProviderId("oidc.provider-id")
            .setDisplayName("DISPLAY_NAME")
            .setEnabled(true)
            .setClientId("CLIENT_ID")
            .setIssuer("https://oidc.com/issuer");
    TenantAwareFirebaseAuth tenantAwareAuth =
        FirebaseAuth.getInstance().getTenantManager().getAuthForTenant("TENANT_ID");

    OidcProviderConfig config = tenantAwareAuth.createOidcProviderConfig(createRequest);

    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "POST", TENANTS_BASE_URL + "/TENANT_ID/oauthIdpConfigs");
  }

  @Test
  public void testUpdateOidcProvider() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("oidc.json"));
    OidcProviderConfig.UpdateRequest request =
        new OidcProviderConfig.UpdateRequest("oidc.provider-id")
            .setDisplayName("DISPLAY_NAME")
            .setEnabled(true)
            .setClientId("CLIENT_ID")
            .setIssuer("https://oidc.com/issuer");

    OidcProviderConfig config = FirebaseAuth.getInstance().updateOidcProviderConfig(request);

    checkOidcProviderConfig(config, "oidc.provider-id");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "PATCH", PROJECT_BASE_URL + "/oauthIdpConfigs/oidc.provider-id");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals("clientId,displayName,enabled,issuer", url.getFirst("updateMask"));
    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals("DISPLAY_NAME", parsed.get("displayName"));
    assertTrue((boolean) parsed.get("enabled"));
    assertEquals("CLIENT_ID", parsed.get("clientId"));
    assertEquals("https://oidc.com/issuer", parsed.get("issuer"));
  }

  @Test
  public void testUpdateOidcProviderMinimal() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("oidc.json"));
    OidcProviderConfig.UpdateRequest request =
        new OidcProviderConfig.UpdateRequest("oidc.provider-id").setDisplayName("DISPLAY_NAME");

    OidcProviderConfig config = FirebaseAuth.getInstance().updateOidcProviderConfig(request);

    checkOidcProviderConfig(config, "oidc.provider-id");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "PATCH", PROJECT_BASE_URL + "/oauthIdpConfigs/oidc.provider-id");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals("displayName", url.getFirst("updateMask"));
    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals(1, parsed.size());
    assertEquals("DISPLAY_NAME", parsed.get("displayName"));
  }

  @Test
  public void testUpdateOidcProviderConfigNoValues() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("oidc.json"));
    try {
      FirebaseAuth.getInstance().updateOidcProviderConfig(
          new OidcProviderConfig.UpdateRequest("oidc.provider-id"));
      fail("No error thrown for empty provider config update");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }

  @Test
  public void testUpdateOidcProviderConfigError() throws Exception {
    TestResponseInterceptor interceptor =
        initializeAppForUserManagementWithStatusCode(404,
            "{\"error\": {\"message\": \"INTERNAL_ERROR\"}}");
    OidcProviderConfig.UpdateRequest request =
        new OidcProviderConfig.UpdateRequest("oidc.provider-id").setDisplayName("DISPLAY_NAME");
    try {
      FirebaseAuth.getInstance().updateOidcProviderConfig(request);
      fail("No error thrown for invalid response");
    } catch (FirebaseAuthException e) {
      assertEquals(FirebaseUserManager.INTERNAL_ERROR, e.getErrorCode());
    }
    checkUrl(interceptor, "PATCH", PROJECT_BASE_URL + "/oauthIdpConfigs/oidc.provider-id");
  }

  @Test
  public void testTenantAwareUpdateOidcProvider() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForTenantAwareUserManagement(
        "TENANT_ID",
        TestUtils.loadResource("oidc.json"));
    TenantAwareFirebaseAuth tenantAwareAuth =
        FirebaseAuth.getInstance().getTenantManager().getAuthForTenant("TENANT_ID");
    OidcProviderConfig.UpdateRequest request =
        new OidcProviderConfig.UpdateRequest("oidc.provider-id")
            .setDisplayName("DISPLAY_NAME")
            .setEnabled(true)
            .setClientId("CLIENT_ID")
            .setIssuer("https://oidc.com/issuer");

    OidcProviderConfig config = tenantAwareAuth.updateOidcProviderConfig(request);

    checkOidcProviderConfig(config, "oidc.provider-id");
    checkRequestHeaders(interceptor);
    String expectedUrl = TENANTS_BASE_URL + "/TENANT_ID/oauthIdpConfigs/oidc.provider-id";
    checkUrl(interceptor, "PATCH", expectedUrl);
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals("clientId,displayName,enabled,issuer", url.getFirst("updateMask"));
    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals("DISPLAY_NAME", parsed.get("displayName"));
    assertTrue((boolean) parsed.get("enabled"));
    assertEquals("CLIENT_ID", parsed.get("clientId"));
    assertEquals("https://oidc.com/issuer", parsed.get("issuer"));
  }

  @Test
  public void testGetOidcProviderConfig() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("oidc.json"));

    OidcProviderConfig config =
        FirebaseAuth.getInstance().getOidcProviderConfig("oidc.provider-id");

    checkOidcProviderConfig(config, "oidc.provider-id");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "GET", PROJECT_BASE_URL + "/oauthIdpConfigs/oidc.provider-id");
  }

  @Test
  public void testGetOidcProviderConfigMissingId() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("oidc.json"));

    try {
      FirebaseAuth.getInstance().getOidcProviderConfig(null);
      fail("No error thrown for missing provider ID.");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  @Test
  public void testGetOidcProviderConfigInvalidId() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("oidc.json"));

    try {
      FirebaseAuth.getInstance().getOidcProviderConfig("saml.invalid-oidc-provider-id");
      fail("No error thrown for invalid provider ID.");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  @Test
  public void testGetOidcProviderConfigWithNotFoundError() throws Exception {
    TestResponseInterceptor interceptor =
        initializeAppForUserManagementWithStatusCode(404,
            "{\"error\": {\"message\": \"CONFIGURATION_NOT_FOUND\"}}");
    try {
      FirebaseAuth.getInstance().getOidcProviderConfig("oidc.provider-id");
      fail("No error thrown for invalid response");
    } catch (FirebaseAuthException e) {
      assertEquals(FirebaseUserManager.CONFIGURATION_NOT_FOUND_ERROR, e.getErrorCode());
    }
    checkUrl(interceptor, "GET", PROJECT_BASE_URL + "/oauthIdpConfigs/oidc.provider-id");
  }

  @Test
  public void testGetTenantAwareOidcProviderConfig() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForTenantAwareUserManagement(
        "TENANT_ID",
        TestUtils.loadResource("oidc.json"));
    TenantAwareFirebaseAuth tenantAwareAuth =
        FirebaseAuth.getInstance().getTenantManager().getAuthForTenant("TENANT_ID");

    OidcProviderConfig config = tenantAwareAuth.getOidcProviderConfig("oidc.provider-id");

    checkOidcProviderConfig(config, "oidc.provider-id");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "GET", TENANTS_BASE_URL + "/TENANT_ID/oauthIdpConfigs/oidc.provider-id");
  }

  @Test
  public void testListOidcProviderConfigs() throws Exception {
    final TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("listOidc.json"));
    ListProviderConfigsPage<OidcProviderConfig> page =
        FirebaseAuth.getInstance().listOidcProviderConfigsAsync(null, 99).get();

    ImmutableList<OidcProviderConfig> providerConfigs = ImmutableList.copyOf(page.getValues());
    assertEquals(2, providerConfigs.size());
    checkOidcProviderConfig(providerConfigs.get(0), "oidc.provider-id1");
    checkOidcProviderConfig(providerConfigs.get(1), "oidc.provider-id2");
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "GET", PROJECT_BASE_URL + "/oauthIdpConfigs");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals(99, url.getFirst("pageSize"));
    assertNull(url.getFirst("nextPageToken"));
  }

  @Test
  public void testListOidcProviderConfigsWithPageToken() throws Exception {
    final TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("listOidc.json"));
    ListProviderConfigsPage<OidcProviderConfig> page =
        FirebaseAuth.getInstance().listOidcProviderConfigsAsync("token", 99).get();

    ImmutableList<OidcProviderConfig> providerConfigs = ImmutableList.copyOf(page.getValues());
    assertEquals(2, providerConfigs.size());
    checkOidcProviderConfig(providerConfigs.get(0), "oidc.provider-id1");
    checkOidcProviderConfig(providerConfigs.get(1), "oidc.provider-id2");
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "GET", PROJECT_BASE_URL + "/oauthIdpConfigs");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals(99, url.getFirst("pageSize"));
    assertEquals("token", url.getFirst("nextPageToken"));
  }

  @Test
  public void testListZeroOidcProviderConfigs() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement("{}");
    ListProviderConfigsPage<OidcProviderConfig> page =
        FirebaseAuth.getInstance().listOidcProviderConfigsAsync(null).get();
    assertTrue(Iterables.isEmpty(page.getValues()));
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);
  }

  @Test
  public void testTenantAwareListOidcProviderConfigs() throws Exception {
    final TestResponseInterceptor interceptor = initializeAppForTenantAwareUserManagement(
        "TENANT_ID",
        TestUtils.loadResource("listOidc.json"));
    TenantAwareFirebaseAuth tenantAwareAuth =
        FirebaseAuth.getInstance().getTenantManager().getAuthForTenant("TENANT_ID");
    ListProviderConfigsPage<OidcProviderConfig> page =
        tenantAwareAuth.listOidcProviderConfigsAsync(null, 99).get();

    ImmutableList<OidcProviderConfig> providerConfigs = ImmutableList.copyOf(page.getValues());
    assertEquals(2, providerConfigs.size());
    checkOidcProviderConfig(providerConfigs.get(0), "oidc.provider-id1");
    checkOidcProviderConfig(providerConfigs.get(1), "oidc.provider-id2");
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "GET", TENANTS_BASE_URL + "/TENANT_ID/oauthIdpConfigs");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals(99, url.getFirst("pageSize"));
    assertNull(url.getFirst("nextPageToken"));
  }

  @Test
  public void testDeleteOidcProviderConfig() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement("{}");

    FirebaseAuth.getInstance().deleteOidcProviderConfig("oidc.provider-id");

    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "DELETE", PROJECT_BASE_URL + "/oauthIdpConfigs/oidc.provider-id");
  }

  @Test
  public void testDeleteOidcProviderMissingId() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement("{}");

    try {
      FirebaseAuth.getInstance().deleteOidcProviderConfig(null);
      fail("No error thrown for missing provider ID.");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  @Test
  public void testDeleteOidcProviderInvalidId() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement("{}");

    try {
      FirebaseAuth.getInstance().deleteOidcProviderConfig("saml.invalid-oidc-provider-id");
      fail("No error thrown for invalid provider ID.");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  @Test
  public void testDeleteOidcProviderConfigWithNotFoundError() throws Exception {
    TestResponseInterceptor interceptor =
        initializeAppForUserManagementWithStatusCode(404,
            "{\"error\": {\"message\": \"CONFIGURATION_NOT_FOUND\"}}");
    try {
      FirebaseAuth.getInstance().deleteOidcProviderConfig("oidc.UNKNOWN");
      fail("No error thrown for invalid response");
    } catch (FirebaseAuthException e) {
      assertEquals(FirebaseUserManager.CONFIGURATION_NOT_FOUND_ERROR, e.getErrorCode());
    }
    checkUrl(interceptor, "DELETE", PROJECT_BASE_URL + "/oauthIdpConfigs/oidc.UNKNOWN");
  }

  @Test
  public void testTenantAwareDeleteOidcProviderConfig() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForTenantAwareUserManagement(
        "TENANT_ID",
        "{}");
    TenantAwareFirebaseAuth tenantAwareAuth =
        FirebaseAuth.getInstance().getTenantManager().getAuthForTenant("TENANT_ID");

    tenantAwareAuth.deleteOidcProviderConfig("oidc.provider-id");

    checkRequestHeaders(interceptor);
    String expectedUrl = TENANTS_BASE_URL + "/TENANT_ID/oauthIdpConfigs/oidc.provider-id";
    checkUrl(interceptor, "DELETE", expectedUrl);
  }

  @Test
  public void testCreateSamlProvider() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("saml.json"));
    SamlProviderConfig.CreateRequest createRequest =
        new SamlProviderConfig.CreateRequest()
          .setProviderId("saml.provider-id")
          .setDisplayName("DISPLAY_NAME")
          .setEnabled(true)
          .setIdpEntityId("IDP_ENTITY_ID")
          .setSsoUrl("https://example.com/login")
          .addX509Certificate("certificate1")
          .addX509Certificate("certificate2")
          .setRpEntityId("RP_ENTITY_ID")
          .setCallbackUrl("https://projectId.firebaseapp.com/__/auth/handler");

    SamlProviderConfig config = FirebaseAuth.getInstance().createSamlProviderConfig(createRequest);

    checkSamlProviderConfig(config, "saml.provider-id");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "POST", PROJECT_BASE_URL + "/inboundSamlConfigs");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals("saml.provider-id", url.getFirst("inboundSamlConfigId"));

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals("DISPLAY_NAME", parsed.get("displayName"));
    assertTrue((boolean) parsed.get("enabled"));

    Map<String, Object> idpConfig = (Map<String, Object>) parsed.get("idpConfig");
    assertNotNull(idpConfig);
    assertEquals(3, idpConfig.size());
    assertEquals("IDP_ENTITY_ID", idpConfig.get("idpEntityId"));
    assertEquals("https://example.com/login", idpConfig.get("ssoUrl"));
    List<Object> idpCertificates = (List<Object>) idpConfig.get("idpCertificates");
    assertNotNull(idpCertificates);
    assertEquals(2, idpCertificates.size());
    assertEquals(ImmutableMap.of("x509Certificate", "certificate1"), idpCertificates.get(0));
    assertEquals(ImmutableMap.of("x509Certificate", "certificate2"), idpCertificates.get(1));

    Map<String, Object> spConfig = (Map<String, Object>) parsed.get("spConfig");
    assertNotNull(spConfig);
    assertEquals(2, spConfig.size());
    assertEquals("RP_ENTITY_ID", spConfig.get("spEntityId"));
    assertEquals("https://projectId.firebaseapp.com/__/auth/handler", spConfig.get("callbackUri"));
  }

  @Test
  public void testCreateSamlProviderMinimal() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("saml.json"));
    // Only the 'enabled', 'displayName', and 'signRequest' fields can be omitted from a SAML
    // provider config creation request.
    SamlProviderConfig.CreateRequest createRequest =
        new SamlProviderConfig.CreateRequest()
          .setProviderId("saml.provider-id")
          .setIdpEntityId("IDP_ENTITY_ID")
          .setSsoUrl("https://example.com/login")
          .addX509Certificate("certificate")
          .setRpEntityId("RP_ENTITY_ID")
          .setCallbackUrl("https://projectId.firebaseapp.com/__/auth/handler");

    FirebaseAuth.getInstance().createSamlProviderConfig(createRequest);

    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "POST", PROJECT_BASE_URL + "/inboundSamlConfigs");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals("saml.provider-id", url.getFirst("inboundSamlConfigId"));

    GenericJson parsed = parseRequestContent(interceptor);
    assertNull(parsed.get("displayName"));
    assertNull(parsed.get("enabled"));
    Map<String, Object> idpConfig = (Map<String, Object>) parsed.get("idpConfig");
    assertNotNull(idpConfig);
    assertEquals(3, idpConfig.size());
    assertEquals("IDP_ENTITY_ID", idpConfig.get("idpEntityId"));
    assertEquals("https://example.com/login", idpConfig.get("ssoUrl"));
    List<Object> idpCertificates = (List<Object>) idpConfig.get("idpCertificates");
    assertNotNull(idpCertificates);
    assertEquals(1, idpCertificates.size());
    assertEquals(ImmutableMap.of("x509Certificate", "certificate"), idpCertificates.get(0));
    Map<String, Object> spConfig = (Map<String, Object>) parsed.get("spConfig");
    assertNotNull(spConfig);
    assertEquals(2, spConfig.size());
    assertEquals("RP_ENTITY_ID", spConfig.get("spEntityId"));
    assertEquals("https://projectId.firebaseapp.com/__/auth/handler", spConfig.get("callbackUri"));
  }

  @Test
  public void testCreateSamlProviderError() throws Exception {
    TestResponseInterceptor interceptor =
        initializeAppForUserManagementWithStatusCode(404,
            "{\"error\": {\"message\": \"INTERNAL_ERROR\"}}");
    SamlProviderConfig.CreateRequest createRequest =
        new SamlProviderConfig.CreateRequest().setProviderId("saml.provider-id");
    try {
      FirebaseAuth.getInstance().createSamlProviderConfig(createRequest);
      fail("No error thrown for invalid response");
    } catch (FirebaseAuthException e) {
      assertEquals(FirebaseUserManager.INTERNAL_ERROR, e.getErrorCode());
    }
    checkUrl(interceptor, "POST", PROJECT_BASE_URL + "/inboundSamlConfigs");
  }

  @Test
  public void testCreateSamlProviderMissingId() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("saml.json"));
    SamlProviderConfig.CreateRequest createRequest =
        new SamlProviderConfig.CreateRequest()
          .setDisplayName("DISPLAY_NAME")
          .setEnabled(true)
          .setIdpEntityId("IDP_ENTITY_ID")
          .setSsoUrl("https://example.com/login")
          .addX509Certificate("certificate1")
          .addX509Certificate("certificate2")
          .setRpEntityId("RP_ENTITY_ID")
          .setCallbackUrl("https://projectId.firebaseapp.com/__/auth/handler");
    try {
      FirebaseAuth.getInstance().createSamlProviderConfig(createRequest);
      fail("No error thrown for invalid response");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  @Test
  public void testTenantAwareCreateSamlProvider() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForTenantAwareUserManagement(
        "TENANT_ID",
        TestUtils.loadResource("saml.json"));
    SamlProviderConfig.CreateRequest createRequest =
        new SamlProviderConfig.CreateRequest()
          .setProviderId("saml.provider-id")
          .setDisplayName("DISPLAY_NAME")
          .setEnabled(true)
          .setIdpEntityId("IDP_ENTITY_ID")
          .setSsoUrl("https://example.com/login")
          .addX509Certificate("certificate1")
          .addX509Certificate("certificate2")
          .setRpEntityId("RP_ENTITY_ID")
          .setCallbackUrl("https://projectId.firebaseapp.com/__/auth/handler");
    TenantAwareFirebaseAuth tenantAwareAuth =
        FirebaseAuth.getInstance().getTenantManager().getAuthForTenant("TENANT_ID");

    tenantAwareAuth.createSamlProviderConfig(createRequest);

    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "POST", TENANTS_BASE_URL + "/TENANT_ID/inboundSamlConfigs");
  }

  @Test
  public void testUpdateSamlProvider() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("saml.json"));
    SamlProviderConfig.UpdateRequest updateRequest =
        new SamlProviderConfig.UpdateRequest("saml.provider-id")
          .setDisplayName("DISPLAY_NAME")
          .setEnabled(true)
          .setIdpEntityId("IDP_ENTITY_ID")
          .setSsoUrl("https://example.com/login")
          .addX509Certificate("certificate1")
          .addX509Certificate("certificate2")
          .setRpEntityId("RP_ENTITY_ID")
          .setCallbackUrl("https://projectId.firebaseapp.com/__/auth/handler");

    SamlProviderConfig config = FirebaseAuth.getInstance().updateSamlProviderConfig(updateRequest);

    checkSamlProviderConfig(config, "saml.provider-id");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "PATCH", PROJECT_BASE_URL + "/inboundSamlConfigs/saml.provider-id");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals(
        "displayName,enabled,idpConfig.idpCertificates,idpConfig.idpEntityId,idpConfig.ssoUrl,"
          + "spConfig.callbackUri,spConfig.spEntityId",
        url.getFirst("updateMask"));

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals("DISPLAY_NAME", parsed.get("displayName"));
    assertTrue((boolean) parsed.get("enabled"));

    Map<String, Object> idpConfig = (Map<String, Object>) parsed.get("idpConfig");
    assertNotNull(idpConfig);
    assertEquals(3, idpConfig.size());
    assertEquals("IDP_ENTITY_ID", idpConfig.get("idpEntityId"));
    assertEquals("https://example.com/login", idpConfig.get("ssoUrl"));
    List<Object> idpCertificates = (List<Object>) idpConfig.get("idpCertificates");
    assertNotNull(idpCertificates);
    assertEquals(2, idpCertificates.size());
    assertEquals(ImmutableMap.of("x509Certificate", "certificate1"), idpCertificates.get(0));
    assertEquals(ImmutableMap.of("x509Certificate", "certificate2"), idpCertificates.get(1));

    Map<String, Object> spConfig = (Map<String, Object>) parsed.get("spConfig");
    assertNotNull(spConfig);
    assertEquals(2, spConfig.size());
    assertEquals("RP_ENTITY_ID", spConfig.get("spEntityId"));
    assertEquals("https://projectId.firebaseapp.com/__/auth/handler", spConfig.get("callbackUri"));
  }

  @Test
  public void testUpdateSamlProviderMinimal() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("saml.json"));
    SamlProviderConfig.UpdateRequest request =
        new SamlProviderConfig.UpdateRequest("saml.provider-id").setDisplayName("DISPLAY_NAME");

    SamlProviderConfig config = FirebaseAuth.getInstance().updateSamlProviderConfig(request);

    checkSamlProviderConfig(config, "saml.provider-id");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "PATCH", PROJECT_BASE_URL + "/inboundSamlConfigs/saml.provider-id");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals("displayName", url.getFirst("updateMask"));
    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals(1, parsed.size());
    assertEquals("DISPLAY_NAME", parsed.get("displayName"));
  }

  @Test
  public void testUpdateSamlProviderConfigNoValues() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("saml.json"));
    try {
      FirebaseAuth.getInstance().updateSamlProviderConfig(
          new SamlProviderConfig.UpdateRequest("saml.provider-id"));
      fail("No error thrown for empty provider config update");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }

  @Test
  public void testUpdateSamlProviderConfigError() throws Exception {
    TestResponseInterceptor interceptor =
        initializeAppForUserManagementWithStatusCode(404,
            "{\"error\": {\"message\": \"INTERNAL_ERROR\"}}");
    SamlProviderConfig.UpdateRequest request =
        new SamlProviderConfig.UpdateRequest("saml.provider-id").setDisplayName("DISPLAY_NAME");
    try {
      FirebaseAuth.getInstance().updateSamlProviderConfig(request);
      fail("No error thrown for invalid response");
    } catch (FirebaseAuthException e) {
      assertEquals(FirebaseUserManager.INTERNAL_ERROR, e.getErrorCode());
    }
    checkUrl(interceptor, "PATCH", PROJECT_BASE_URL + "/inboundSamlConfigs/saml.provider-id");
  }

  @Test
  public void testTenantAwareUpdateSamlProvider() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForTenantAwareUserManagement(
        "TENANT_ID",
        TestUtils.loadResource("saml.json"));
    TenantAwareFirebaseAuth tenantAwareAuth =
        FirebaseAuth.getInstance().getTenantManager().getAuthForTenant("TENANT_ID");
    SamlProviderConfig.UpdateRequest updateRequest =
        new SamlProviderConfig.UpdateRequest("saml.provider-id")
          .setDisplayName("DISPLAY_NAME")
          .setEnabled(true)
          .setIdpEntityId("IDP_ENTITY_ID")
          .setSsoUrl("https://example.com/login");

    SamlProviderConfig config = tenantAwareAuth.updateSamlProviderConfig(updateRequest);

    checkSamlProviderConfig(config, "saml.provider-id");
    checkRequestHeaders(interceptor);
    String expectedUrl = TENANTS_BASE_URL + "/TENANT_ID/inboundSamlConfigs/saml.provider-id";
    checkUrl(interceptor, "PATCH", expectedUrl);
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals("displayName,enabled,idpConfig.idpEntityId,idpConfig.ssoUrl",
        url.getFirst("updateMask"));

    GenericJson parsed = parseRequestContent(interceptor);
    assertEquals("DISPLAY_NAME", parsed.get("displayName"));
    assertTrue((boolean) parsed.get("enabled"));
    Map<String, Object> idpConfig = (Map<String, Object>) parsed.get("idpConfig");
    assertNotNull(idpConfig);
    assertEquals(2, idpConfig.size());
    assertEquals("IDP_ENTITY_ID", idpConfig.get("idpEntityId"));
    assertEquals("https://example.com/login", idpConfig.get("ssoUrl"));
  }

  @Test
  public void testGetSamlProviderConfig() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("saml.json"));

    SamlProviderConfig config =
        FirebaseAuth.getInstance().getSamlProviderConfig("saml.provider-id");

    checkSamlProviderConfig(config, "saml.provider-id");
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "GET", PROJECT_BASE_URL + "/inboundSamlConfigs/saml.provider-id");
  }

  @Test
  public void testGetSamlProviderConfigMissingId() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("saml.json"));

    try {
      FirebaseAuth.getInstance().getSamlProviderConfig(null);
      fail("No error thrown for missing provider ID.");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  @Test
  public void testGetSamlProviderConfigInvalidId() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("saml.json"));

    try {
      FirebaseAuth.getInstance().getSamlProviderConfig("oidc.invalid-saml-provider-id");
      fail("No error thrown for invalid provider ID.");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  @Test
  public void testGetSamlProviderConfigWithNotFoundError() throws Exception {
    TestResponseInterceptor interceptor =
        initializeAppForUserManagementWithStatusCode(404,
            "{\"error\": {\"message\": \"CONFIGURATION_NOT_FOUND\"}}");
    try {
      FirebaseAuth.getInstance().getSamlProviderConfig("saml.provider-id");
      fail("No error thrown for invalid response");
    } catch (FirebaseAuthException e) {
      assertEquals(FirebaseUserManager.CONFIGURATION_NOT_FOUND_ERROR, e.getErrorCode());
    }
    checkUrl(interceptor, "GET", PROJECT_BASE_URL + "/inboundSamlConfigs/saml.provider-id");
  }

  @Test
  public void testGetTenantAwareSamlProviderConfig() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForTenantAwareUserManagement(
        "TENANT_ID",
        TestUtils.loadResource("saml.json"));
    TenantAwareFirebaseAuth tenantAwareAuth =
        FirebaseAuth.getInstance().getTenantManager().getAuthForTenant("TENANT_ID");

    SamlProviderConfig config = tenantAwareAuth.getSamlProviderConfig("saml.provider-id");

    checkSamlProviderConfig(config, "saml.provider-id");
    checkRequestHeaders(interceptor);
    String expectedUrl = TENANTS_BASE_URL + "/TENANT_ID/inboundSamlConfigs/saml.provider-id";
    checkUrl(interceptor, "GET", expectedUrl);
  }

  @Test
  public void testListSamlProviderConfigs() throws Exception {
    final TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("listSaml.json"));
    ListProviderConfigsPage<SamlProviderConfig> page =
        FirebaseAuth.getInstance().listSamlProviderConfigsAsync(null, 99).get();

    ImmutableList<SamlProviderConfig> providerConfigs = ImmutableList.copyOf(page.getValues());
    assertEquals(2, providerConfigs.size());
    checkSamlProviderConfig(providerConfigs.get(0), "saml.provider-id1");
    checkSamlProviderConfig(providerConfigs.get(1), "saml.provider-id2");
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "GET", PROJECT_BASE_URL + "/inboundSamlConfigs");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals(99, url.getFirst("pageSize"));
    assertNull(url.getFirst("nextPageToken"));
  }

  @Test
  public void testListSamlProviderConfigsWithPageToken() throws Exception {
    final TestResponseInterceptor interceptor = initializeAppForUserManagement(
        TestUtils.loadResource("listSaml.json"));
    ListProviderConfigsPage<SamlProviderConfig> page =
        FirebaseAuth.getInstance().listSamlProviderConfigsAsync("token", 99).get();

    ImmutableList<SamlProviderConfig> providerConfigs = ImmutableList.copyOf(page.getValues());
    assertEquals(2, providerConfigs.size());
    checkSamlProviderConfig(providerConfigs.get(0), "saml.provider-id1");
    checkSamlProviderConfig(providerConfigs.get(1), "saml.provider-id2");
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "GET", PROJECT_BASE_URL + "/inboundSamlConfigs");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals(99, url.getFirst("pageSize"));
    assertEquals("token", url.getFirst("nextPageToken"));
  }

  @Test
  public void testListZeroSamlProviderConfigs() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement("{}");
    ListProviderConfigsPage<SamlProviderConfig> page =
        FirebaseAuth.getInstance().listSamlProviderConfigsAsync(null).get();
    assertTrue(Iterables.isEmpty(page.getValues()));
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);
  }

  @Test
  public void testTenantAwareListSamlProviderConfigs() throws Exception {
    final TestResponseInterceptor interceptor = initializeAppForTenantAwareUserManagement(
        "TENANT_ID",
        TestUtils.loadResource("listSaml.json"));
    TenantAwareFirebaseAuth tenantAwareAuth =
        FirebaseAuth.getInstance().getTenantManager().getAuthForTenant("TENANT_ID");
    ListProviderConfigsPage<SamlProviderConfig> page =
        tenantAwareAuth.listSamlProviderConfigsAsync(null, 99).get();

    ImmutableList<SamlProviderConfig> providerConfigs = ImmutableList.copyOf(page.getValues());
    assertEquals(2, providerConfigs.size());
    checkSamlProviderConfig(providerConfigs.get(0), "saml.provider-id1");
    checkSamlProviderConfig(providerConfigs.get(1), "saml.provider-id2");
    assertEquals("", page.getNextPageToken());
    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "GET", TENANTS_BASE_URL + "/TENANT_ID/inboundSamlConfigs");
    GenericUrl url = interceptor.getResponse().getRequest().getUrl();
    assertEquals(99, url.getFirst("pageSize"));
    assertNull(url.getFirst("nextPageToken"));
  }

  @Test
  public void testDeleteSamlProviderConfig() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement("{}");

    FirebaseAuth.getInstance().deleteSamlProviderConfig("saml.provider-id");

    checkRequestHeaders(interceptor);
    checkUrl(interceptor, "DELETE", PROJECT_BASE_URL + "/inboundSamlConfigs/saml.provider-id");
  }

  @Test
  public void testDeleteSamlProviderMissingId() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement("{}");

    try {
      FirebaseAuth.getInstance().deleteSamlProviderConfig(null);
      fail("No error thrown for missing provider ID.");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  @Test
  public void testDeleteSamlProviderInvalidId() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForUserManagement("{}");

    try {
      FirebaseAuth.getInstance().deleteSamlProviderConfig("oidc.invalid-saml-provider-id");
      fail("No error thrown for invalid provider ID.");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  @Test
  public void testDeleteSamlProviderConfigWithNotFoundError() throws Exception {
    TestResponseInterceptor interceptor =
        initializeAppForUserManagementWithStatusCode(404,
            "{\"error\": {\"message\": \"CONFIGURATION_NOT_FOUND\"}}");
    try {
      FirebaseAuth.getInstance().deleteSamlProviderConfig("saml.UNKNOWN");
      fail("No error thrown for invalid response");
    } catch (FirebaseAuthException e) {
      assertEquals(FirebaseUserManager.CONFIGURATION_NOT_FOUND_ERROR, e.getErrorCode());
    }
    checkUrl(interceptor, "DELETE", PROJECT_BASE_URL + "/inboundSamlConfigs/saml.UNKNOWN");
  }

  @Test
  public void testTenantAwareDeleteSamlProviderConfig() throws Exception {
    TestResponseInterceptor interceptor = initializeAppForTenantAwareUserManagement(
        "TENANT_ID",
        "{}");
    TenantAwareFirebaseAuth tenantAwareAuth =
        FirebaseAuth.getInstance().getTenantManager().getAuthForTenant("TENANT_ID");

    tenantAwareAuth.deleteSamlProviderConfig("saml.provider-id");

    checkRequestHeaders(interceptor);
    String expectedUrl = TENANTS_BASE_URL + "/TENANT_ID/inboundSamlConfigs/saml.provider-id";
    checkUrl(interceptor, "DELETE", expectedUrl);
  }

  private static TestResponseInterceptor initializeAppForUserManagementWithStatusCode(
      int statusCode, String response) {
    FirebaseApp.initializeApp(new FirebaseOptions.Builder()
        .setCredentials(credentials)
        .setHttpTransport(
          new MockHttpTransport.Builder().setLowLevelHttpResponse(
            new MockLowLevelHttpResponse().setContent(response).setStatusCode(statusCode)).build())
        .setProjectId("test-project-id")
        .build());
    TestResponseInterceptor interceptor = new TestResponseInterceptor();
    FirebaseAuth.getInstance().getUserManager().setInterceptor(interceptor);
    return interceptor;
  }

  private static TestResponseInterceptor initializeAppForTenantAwareUserManagement(
      String tenantId,
      String... responses) {
    initializeAppWithResponses(responses);
    TestResponseInterceptor interceptor = new TestResponseInterceptor();
    TenantManager tenantManager =  FirebaseAuth.getInstance().getTenantManager();
    tenantManager.getAuthForTenant(tenantId).getUserManager().setInterceptor(interceptor);
    return interceptor;
  }

  private static TestResponseInterceptor initializeAppForUserManagement(String... responses) {
    initializeAppWithResponses(responses);
    TestResponseInterceptor interceptor = new TestResponseInterceptor();
    FirebaseAuth.getInstance().getUserManager().setInterceptor(interceptor);
    return interceptor;
  }

  private static void initializeAppWithResponses(String... responses) {
    List<MockLowLevelHttpResponse> mocks = new ArrayList<>();
    for (String response : responses) {
      mocks.add(new MockLowLevelHttpResponse().setContent(response));
    }
    MockHttpTransport transport = new MultiRequestMockHttpTransport(mocks);
    FirebaseApp.initializeApp(new FirebaseOptions.Builder()
        .setCredentials(credentials)
        .setHttpTransport(transport)
        .setProjectId("test-project-id")
        .build());
  }

  private static GenericJson parseRequestContent(TestResponseInterceptor interceptor)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    interceptor.getResponse().getRequest().getContent().writeTo(out);
    return JSON_FACTORY.fromString(new String(out.toByteArray()), GenericJson.class);
  }

  private static FirebaseAuth getRetryDisabledAuth(MockLowLevelHttpResponse response) {
    final MockHttpTransport transport = new MockHttpTransport.Builder()
        .setLowLevelHttpResponse(response)
        .build();
    final FirebaseApp app = FirebaseApp.initializeApp(new FirebaseOptions.Builder()
        .setCredentials(credentials)
        .setProjectId("test-project-id")
        .setHttpTransport(transport)
        .build());
    return new FirebaseAuth(
        AbstractFirebaseAuth.builder()
          .setFirebaseApp(app)
          .setUserManager(new Supplier<FirebaseUserManager>() {
            @Override
            public FirebaseUserManager get() {
              return FirebaseUserManager
                .builder()
                .setFirebaseApp(app)
                .setHttpRequestFactory(transport.createRequestFactory())
                .build();
            }
          }));
  }

  private static void checkUserRecord(UserRecord userRecord) {
    assertEquals("testuser", userRecord.getUid());
    assertEquals("testuser@example.com", userRecord.getEmail());
    assertEquals("+1234567890", userRecord.getPhoneNumber());
    assertEquals("Test User", userRecord.getDisplayName());
    assertEquals("http://www.example.com/testuser/photo.png", userRecord.getPhotoUrl());
    assertEquals(1234567890, userRecord.getUserMetadata().getCreationTimestamp());
    assertEquals(0, userRecord.getUserMetadata().getLastSignInTimestamp());
    assertEquals(2, userRecord.getProviderData().length);
    assertFalse(userRecord.isDisabled());
    assertTrue(userRecord.isEmailVerified());
    assertEquals(1494364393000L, userRecord.getTokensValidAfterTimestamp());
    assertEquals("testTenant", userRecord.getTenantId());

    UserInfo provider = userRecord.getProviderData()[0];
    assertEquals("testuser@example.com", provider.getUid());
    assertEquals("testuser@example.com", provider.getEmail());
    assertNull(provider.getPhoneNumber());
    assertEquals("Test User", provider.getDisplayName());
    assertEquals("http://www.example.com/testuser/photo.png", provider.getPhotoUrl());
    assertEquals("password", provider.getProviderId());

    provider = userRecord.getProviderData()[1];
    assertEquals("+1234567890", provider.getUid());
    assertNull(provider.getEmail());
    assertEquals("+1234567890", provider.getPhoneNumber());
    assertEquals("phone", provider.getProviderId());

    Map<String, Object> claims = userRecord.getCustomClaims();
    assertEquals(2, claims.size());
    assertTrue((boolean) claims.get("admin"));
    assertEquals("gold", claims.get("package"));
  }

  private static void checkTenant(Tenant tenant, String tenantId) {
    assertEquals(tenantId, tenant.getTenantId());
    assertEquals("DISPLAY_NAME", tenant.getDisplayName());
    assertTrue(tenant.isPasswordSignInAllowed());
    assertFalse(tenant.isEmailLinkSignInEnabled());
  }

  private static void checkOidcProviderConfig(OidcProviderConfig config, String providerId) {
    assertEquals(providerId, config.getProviderId());
    assertEquals("DISPLAY_NAME", config.getDisplayName());
    assertTrue(config.isEnabled());
    assertEquals("CLIENT_ID", config.getClientId());
    assertEquals("https://oidc.com/issuer", config.getIssuer());
  }

  private static void checkSamlProviderConfig(SamlProviderConfig config, String providerId) {
    assertEquals(providerId, config.getProviderId());
    assertEquals("DISPLAY_NAME", config.getDisplayName());
    assertTrue(config.isEnabled());
    assertEquals("IDP_ENTITY_ID", config.getIdpEntityId());
    assertEquals("https://example.com/login", config.getSsoUrl());
    assertEquals(ImmutableList.of("certificate1", "certificate2"), config.getX509Certificates());
    assertEquals("RP_ENTITY_ID", config.getRpEntityId());
    assertEquals("https://projectId.firebaseapp.com/__/auth/handler", config.getCallbackUrl());
  }

  private static void checkRequestHeaders(TestResponseInterceptor interceptor) {
    HttpHeaders headers = interceptor.getResponse().getRequest().getHeaders();
    String auth = "Bearer " + TEST_TOKEN;
    assertEquals(auth, headers.getFirstHeaderStringValue("Authorization"));

    String clientVersion = "Java/Admin/" + SdkUtils.getVersion();
    assertEquals(clientVersion, headers.getFirstHeaderStringValue("X-Client-Version"));
  }

  private static void checkUrl(TestResponseInterceptor interceptor, String method, String url) {
    HttpRequest request = interceptor.getResponse().getRequest();
    if (method.equals("PATCH")) {
      assertEquals("PATCH",
          request.getHeaders().getFirstHeaderStringValue("X-HTTP-Method-Override"));
      assertEquals("POST", request.getRequestMethod());
    } else {
      assertEquals(method, request.getRequestMethod());
    }
    assertEquals(url, request.getUrl().toString().split("\\?")[0]);
  }

  private interface UserManagerOp {
    void call(FirebaseAuth auth) throws Exception;
  }

}
