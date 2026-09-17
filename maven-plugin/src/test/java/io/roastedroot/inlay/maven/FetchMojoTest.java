package io.roastedroot.inlay.maven;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sigstore.bundle.Bundle;
import dev.sigstore.strings.StringMatcher;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FetchMojoTest {

    /** Manifest digest of the recorded DSSE fixture, which is also its in-toto subject. */
    private static final String MANIFEST_DIGEST =
            "sha256:acd1363f4ee3194fc09ed7e0b196440c1476b8c088deeba942eb0bf000d3f62c";

    @TempDir Path tempDir;

    @Test
    void missingImageRefAndPackageRefFails() throws Exception {
        FetchMojo mojo = createMojo(new ModuleConfig());

        MojoExecutionException ex =
                assertThrows(MojoExecutionException.class, () -> mojo.execute());
        assertTrue(ex.getMessage().contains("imageRef or packageRef"));
    }

    @Test
    void invalidImageRefWithoutSlashFails() throws Exception {
        ModuleConfig module = new ModuleConfig();
        module.setImageRef("no-slash-here");
        FetchMojo mojo = createMojo(module);

        MojoExecutionException ex =
                assertThrows(MojoExecutionException.class, () -> mojo.execute());
        assertTrue(ex.getMessage().contains("Invalid imageRef"));
    }

    @Test
    void defaultOutputFileStandard() throws Exception {
        File result = invokeDefaultOutputFile("registry.io/ns/module:1.0.0");
        assertEquals("module.wasm", result.getName());
    }

    @Test
    void defaultOutputFileWithDigest() throws Exception {
        File result = invokeDefaultOutputFile("registry.io/ns/module@sha256:abc123");
        assertEquals("module.wasm", result.getName());
    }

    @Test
    void defaultOutputFileNoTag() throws Exception {
        File result = invokeDefaultOutputFile("registry.io/ns/module");
        assertEquals("module.wasm", result.getName());
    }

    @Test
    void defaultOutputFileDeeplyNested() throws Exception {
        File result = invokeDefaultOutputFile("registry.io/org/sub/module:v1");
        assertEquals("module.wasm", result.getName());
    }

    @Test
    void dsseBundleSelectsTheManifestDigest() throws Exception {
        byte[] selected =
                FetchMojo.selectArtifactDigest(
                        loadBundle("cosign-sign-dsse-bundle.json"), MANIFEST_DIGEST);

        assertArrayEquals(
                FetchMojo.hexToBytes(MANIFEST_DIGEST.substring("sha256:".length())), selected);
    }

    @Test
    void messageSignatureBundleDefersToTheArtifactBytes() throws Exception {
        assertNull(
                FetchMojo.selectArtifactDigest(
                        loadBundle("sign-blob-message-signature-bundle.json"), MANIFEST_DIGEST));
    }

    @Test
    void dsseBundleRejectsNonSha256ManifestDigest() throws Exception {
        Bundle bundle = loadBundle("cosign-sign-dsse-bundle.json");

        MojoExecutionException ex =
                assertThrows(
                        MojoExecutionException.class,
                        () -> FetchMojo.selectArtifactDigest(bundle, "sha512:abcdef"));
        assertTrue(ex.getMessage().contains("Unsupported manifest digest algorithm"));
    }

    @Test
    void exactIdentityMatchesOnlyItself() throws Exception {
        StringMatcher matcher = FetchMojo.matcherFor("Identity", "https://github.com/o/r", null);

        assertTrue(matcher.test("https://github.com/o/r"));
        assertFalse(matcher.test("https://github.com/o/rr"));
    }

    @Test
    void exactIdentityDoesNotTreatStarAsAWildcard() throws Exception {
        StringMatcher matcher =
                FetchMojo.matcherFor("Identity", "https://github.com/myorg/*", null);

        assertFalse(matcher.test("https://github.com/myorg/repo"));
        assertTrue(matcher.test("https://github.com/myorg/*"));
    }

    @Test
    void regexIdentityMatchesAPattern() throws Exception {
        StringMatcher matcher =
                FetchMojo.matcherFor("Identity", null, "https://github\\.com/myorg/.*");

        assertTrue(matcher.test("https://github.com/myorg/repo"));
        assertFalse(matcher.test("https://github.com/other/repo"));
    }

    @Test
    void noIdentityConfiguredLeavesTheFieldUnconstrained() throws Exception {
        assertNull(FetchMojo.matcherFor("Identity", null, null));
    }

    @Test
    void exactAndRegexTogetherIsRejected() {
        MojoExecutionException ex =
                assertThrows(
                        MojoExecutionException.class,
                        () -> FetchMojo.matcherFor("Issuer", "a", "b"));
        assertTrue(ex.getMessage().contains("sigstoreIssuer"));
        assertTrue(ex.getMessage().contains("sigstoreIssuerRegex"));
    }

    @Test
    void invalidRegexIsReportedAgainstItsParameter() {
        MojoExecutionException ex =
                assertThrows(
                        MojoExecutionException.class,
                        () -> FetchMojo.matcherFor("Identity", null, "[unclosed"));
        assertTrue(ex.getMessage().contains("sigstoreIdentityRegex"));
    }

    @Test
    void hexToBytesRoundTrips() throws Exception {
        assertArrayEquals(new byte[] {0x00, (byte) 0xff, 0x1a}, FetchMojo.hexToBytes("00ff1a"));
    }

    @Test
    void hexToBytesRejectsMalformedInput() {
        assertThrows(MojoExecutionException.class, () -> FetchMojo.hexToBytes("abc"));
        assertThrows(MojoExecutionException.class, () -> FetchMojo.hexToBytes("zz"));
    }

    @Test
    void detectsTheUpstreamMissingSubjectNameFailure() {
        Exception cause =
                new IllegalStateException(
                        "Cannot build Subject, some of required attributes are not set [name]");
        Exception wrapped = new RuntimeException("Could not parse DSSE payload", cause);

        assertTrue(FetchMojo.isMissingSubjectName(wrapped));
    }

    @Test
    void unrelatedFailuresAreNotMistakenForTheUpstreamBug() {
        assertFalse(
                FetchMojo.isMissingSubjectName(
                        new RuntimeException("DSSE signature was not valid")));
    }

    private static Bundle loadBundle(String fixture) throws Exception {
        try (InputStream in = FetchMojoTest.class.getResourceAsStream("/" + fixture)) {
            assertNotNull(in, "missing fixture: " + fixture);
            return Bundle.from(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private File invokeDefaultOutputFile(String imageRef) throws Exception {
        FetchMojo mojo = createMojoWithProject();
        Method method = FetchMojo.class.getDeclaredMethod("defaultOutputFile", String.class);
        method.setAccessible(true);
        return (File) method.invoke(mojo, imageRef);
    }

    private FetchMojo createMojo(ModuleConfig module) throws Exception {
        FetchMojo mojo = new FetchMojo();
        setField(mojo, "modules", List.of(module));
        setField(mojo, "lockFile", tempDir.resolve("wkg.lock").toFile());
        setField(mojo, "update", false);
        setField(mojo, "noCache", true);
        setField(mojo, "settings", new Settings());
        return mojo;
    }

    private FetchMojo createMojoWithProject() throws Exception {
        FetchMojo mojo = createMojo(new ModuleConfig());
        MavenProject project = new MavenProject();
        Build build = new Build();
        build.setDirectory(tempDir.resolve("target").toString());
        project.setBuild(build);
        setField(mojo, "project", project);
        return mojo;
    }

    static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
