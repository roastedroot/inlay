package io.roastedroot.inlay.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import land.oras.ContainerRef;
import land.oras.LocalPath;
import land.oras.Registry;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FetchMojoIT {

    @Container
    static final GenericContainer<?> ZOT =
            new GenericContainer<>("ghcr.io/project-zot/zot:latest")
                    .withExposedPorts(5000)
                    .waitingFor(Wait.forHttp("/v2/").forStatusCode(200));

    private static String registryUrl;

    @BeforeAll
    static void setup() {
        registryUrl = "localhost:" + ZOT.getMappedPort(5000);
    }

    @TempDir Path tempDir;

    private static final byte[] WASM_MAGIC = {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00};

    private Registry insecureRegistry() {
        return Registry.builder().insecure().build();
    }

    private String pushTestWasm(String name) throws Exception {
        Path wasmFile = tempDir.resolve(name + ".wasm");
        Files.write(wasmFile, WASM_MAGIC);
        String ref = registryUrl + "/test/" + name + ":1.0.0";
        insecureRegistry()
                .pushArtifact(ContainerRef.parse(ref), LocalPath.of(wasmFile, "application/wasm"));
        return ref;
    }

    @Test
    void fetchModuleHappyPath() throws Exception {
        String ref = pushTestWasm("mojo-happy");

        File outputFile = tempDir.resolve("output/mojo-happy.wasm").toFile();
        ModuleConfig module = new ModuleConfig();
        module.setImageRef(ref);
        module.setOutputFile(outputFile);

        FetchMojo mojo = createMojo(module);
        mojo.execute();

        assertTrue(outputFile.exists());
        Path lockPath = tempDir.resolve("wkg.lock");
        assertTrue(Files.exists(lockPath));
        String lockContent = Files.readString(lockPath);
        assertTrue(lockContent.contains("[[packages]]"));
    }

    @Test
    void fetchModuleDigestMismatchFails() throws Exception {
        String ref = pushTestWasm("mojo-mismatch");

        File outputFile = tempDir.resolve("output/mojo-mismatch.wasm").toFile();
        ModuleConfig module = new ModuleConfig();
        module.setImageRef(ref);
        module.setOutputFile(outputFile);

        Path lockPath = tempDir.resolve("wkg.lock");
        Files.writeString(
                lockPath,
                "version = 1\n\n"
                        + "[[packages]]\n"
                        + "name = \"test:mojo-mismatch\"\n"
                        + "registry = \""
                        + registryUrl
                        + "\"\n\n"
                        + "[[packages.versions]]\n"
                        + "requirement = \"=1.0.0\"\n"
                        + "version = \"1.0.0\"\n"
                        + "digest ="
                        + " \"sha256:0000000000000000000000000000000000000000000000000000000000000000\"\n");

        FetchMojo mojo = createMojo(module);
        MojoExecutionException ex =
                assertThrows(MojoExecutionException.class, () -> mojo.execute());
        assertTrue(ex.getMessage().contains("Digest mismatch"));
    }

    @Test
    void fetchModuleUpdateAcceptsNewDigest() throws Exception {
        String ref = pushTestWasm("mojo-update");

        File outputFile = tempDir.resolve("output/mojo-update.wasm").toFile();
        ModuleConfig module = new ModuleConfig();
        module.setImageRef(ref);
        module.setOutputFile(outputFile);

        Path lockPath = tempDir.resolve("wkg.lock");
        Files.writeString(
                lockPath,
                "version = 1\n\n"
                        + "[[packages]]\n"
                        + "name = \"test:mojo-update\"\n"
                        + "registry = \""
                        + registryUrl
                        + "\"\n\n"
                        + "[[packages.versions]]\n"
                        + "requirement = \"=1.0.0\"\n"
                        + "version = \"1.0.0\"\n"
                        + "digest ="
                        + " \"sha256:0000000000000000000000000000000000000000000000000000000000000000\"\n");

        FetchMojo mojo = createMojo(module, true);
        mojo.execute();

        assertTrue(outputFile.exists());
        String updatedLock = Files.readString(lockPath);
        assertTrue(updatedLock.contains("sha256:"));
    }

    @Test
    void fetchModuleDefaultOutputFile() throws Exception {
        String ref = pushTestWasm("mojo-default-out");

        ModuleConfig module = new ModuleConfig();
        module.setImageRef(ref);

        FetchMojo mojo = createMojo(module);
        mojo.execute();

        Path expectedOutput = tempDir.resolve("target/wasm/mojo-default-out.wasm");
        assertTrue(Files.exists(expectedOutput));
    }

    @Test
    void failedVerificationLeavesNoTempFilesBehind() throws Exception {
        String ref = pushTestWasm("mojo-verify-cleanup");

        File outputFile = tempDir.resolve("output/mojo-verify-cleanup.wasm").toFile();
        ModuleConfig module = new ModuleConfig();
        module.setImageRef(ref);
        module.setOutputFile(outputFile);
        module.setSigstoreIssuer("https://token.actions.githubusercontent.com");
        module.setSigstoreIdentity("https://github.com/example/nobody");

        FetchMojo mojo = createMojo(module);
        MojoExecutionException ex =
                assertThrows(MojoExecutionException.class, () -> mojo.execute());
        assertTrue(ex.getMessage().contains("No sigstore bundle found"));

        assertFalse(outputFile.exists(), "unverified artifact must not be published");
        try (Stream<Path> leftovers = Files.list(outputFile.toPath().getParent())) {
            assertEquals(List.of(), leftovers.collect(Collectors.toList()));
        }
    }

    private FetchMojo createMojo(ModuleConfig module) throws Exception {
        return createMojo(module, false);
    }

    private FetchMojo createMojo(ModuleConfig module, boolean updateFlag) throws Exception {
        FetchMojo mojo = new FetchMojo();
        FetchMojoTest.setField(mojo, "modules", List.of(module));
        FetchMojoTest.setField(mojo, "lockFile", tempDir.resolve("wkg.lock").toFile());
        FetchMojoTest.setField(mojo, "update", updateFlag);
        FetchMojoTest.setField(mojo, "noCache", true);
        FetchMojoTest.setField(mojo, "insecure", true);
        FetchMojoTest.setField(mojo, "settings", new Settings());

        MavenProject project = new MavenProject();
        Build build = new Build();
        build.setDirectory(tempDir.resolve("target").toString());
        project.setBuild(build);
        FetchMojoTest.setField(mojo, "project", project);

        return mojo;
    }
}
