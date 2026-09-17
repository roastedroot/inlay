package io.roastedroot.inlay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import land.oras.ArtifactType;
import land.oras.ContainerRef;
import land.oras.Descriptor;
import land.oras.Layer;
import land.oras.LocalPath;
import land.oras.Manifest;
import land.oras.ManifestDescriptor;
import land.oras.Registry;
import land.oras.Subject;
import land.oras.utils.Const;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import run.endive.runtime.ExportFunction;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

@Testcontainers
class InlayClientIT {

    @Container
    static final GenericContainer<?> ZOT =
            new GenericContainer<>("ghcr.io/project-zot/zot:latest")
                    .withExposedPorts(5000)
                    .waitingFor(Wait.forHttp("/v2/").forStatusCode(200));

    private static String registryUrl;
    private static WkgParser parser;

    @BeforeAll
    static void setup() {
        registryUrl = "localhost:" + ZOT.getMappedPort(5000);
        parser = new WkgParser();
    }

    @AfterAll
    static void tearDownParser() {
        if (parser != null) {
            parser.close();
        }
    }

    @TempDir Path tempDir;

    private Registry insecureRegistry() {
        return Registry.builder().insecure().build();
    }

    private String pushTestWasm(String name, byte[] content) throws Exception {
        Path wasmFile = tempDir.resolve(name + ".wasm");
        Files.write(wasmFile, content);
        String ref = registryUrl + "/test/" + name + ":1.0.0";
        insecureRegistry()
                .pushArtifact(ContainerRef.parse(ref), LocalPath.of(wasmFile, "application/wasm"));
        return ref;
    }

    @Test
    void pullFromZot() throws Exception {
        byte[] content = {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00};
        String ref = pushTestWasm("pull-basic", content);

        InlayClient client = InlayClient.builder().insecure().noCache().build();
        Path outputFile = tempDir.resolve("pulled.wasm");
        client.pull(ref, outputFile);

        assertTrue(Files.exists(outputFile));
        assertArrayEquals(content, Files.readAllBytes(outputFile));
    }

    @Test
    void getDigestReturnsValidSha256() throws Exception {
        byte[] content = {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00};
        String ref = pushTestWasm("digest-test", content);

        InlayClient client = InlayClient.builder().insecure().noCache().build();
        String digest = client.getDigest(ref);
        assertNotNull(digest);
        assertTrue(digest.startsWith("sha256:"));
    }

    @Test
    void cacheHitSkipsNetwork() throws Exception {
        byte[] content = {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00};
        String ref = pushTestWasm("cache-test", content);

        Path cacheDir = tempDir.resolve("cache");
        InlayClient client = InlayClient.builder().insecure().withCacheDir(cacheDir).build();

        Path output1 = tempDir.resolve("first.wasm");
        client.pull(ref, output1);
        assertTrue(Files.exists(output1));

        Path output2 = tempDir.resolve("second.wasm");
        client.pull(ref, output2);
        assertTrue(Files.exists(output2));

        assertArrayEquals(Files.readAllBytes(output1), Files.readAllBytes(output2));
    }

    @Test
    void pullNonExistentImageThrows() {
        InlayClient client = InlayClient.builder().insecure().noCache().build();
        assertThrows(
                InlayException.class,
                () ->
                        client.pull(
                                registryUrl + "/nonexistent/image:1.0.0",
                                tempDir.resolve("x.wasm")));
    }

    @Test
    void fetchAndRunOnEndive() throws Exception {
        Path iterfact =
                Path.of(
                        Objects.requireNonNull(
                                        getClass().getClassLoader().getResource("iterfact.wasm"))
                                .toURI());
        byte[] content = Files.readAllBytes(iterfact);
        String ref = pushTestWasm("iterfact", content);

        InlayClient client = InlayClient.builder().insecure().noCache().build();
        Path outputFile = tempDir.resolve("iterfact.wasm");
        client.pull(ref, outputFile);

        WasmModule module = Parser.parse(outputFile.toFile());
        Instance instance = Instance.builder(module).build();

        ExportFunction iterFact = instance.export("iterFact");
        assertNotNull(iterFact);
        assertEquals(120L, iterFact.apply(5)[0]);
    }

    @Test
    void lockFileRoundTripWithRealDigest() throws Exception {
        byte[] content = {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00};
        String ref = pushTestWasm("lock-test", content);

        InlayClient client = InlayClient.builder().insecure().noCache().build();
        String digest = client.getDigest(ref);

        LockFile lock = new LockFile(parser);
        lock.addOrUpdate(ref, digest);

        Path lockPath = tempDir.resolve("wkg.lock");
        lock.write(lockPath);

        LockFile loaded = LockFile.read(parser, lockPath);
        LockedPackage pkg = loaded.findByImageRef(ref);
        assertNotNull(pkg);
        assertEquals(digest, pkg.getDigest());
    }

    @Test
    void pullByDigestPrefersTheDigestOverTheTag() throws Exception {
        byte[] pinned = {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00};
        byte[] other = {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x42};

        String pinnedRef = pushTaggedWasm("digest-pin", "pinned", pinned);
        String otherRef = pushTaggedWasm("digest-pin", "other", other);

        InlayClient client = InlayClient.builder().insecure().noCache().build();
        String pinnedDigest = client.getDigest(pinnedRef);
        assertNotEquals(pinnedDigest, client.getDigest(otherRef));

        // Ask for the other tag while pinning the first digest: the digest must win.
        Path outputFile = tempDir.resolve("pinned.wasm");
        client.pullByDigest(otherRef, pinnedDigest, outputFile);

        assertArrayEquals(pinned, Files.readAllBytes(outputFile));
    }

    private String pushTaggedWasm(String name, String tag, byte[] content) throws Exception {
        Path wasmFile = tempDir.resolve(name + "-" + tag + ".wasm");
        Files.write(wasmFile, content);
        String ref = registryUrl + "/test/" + name + ":" + tag;
        insecureRegistry()
                .pushArtifact(ContainerRef.parse(ref), LocalPath.of(wasmFile, "application/wasm"));
        return ref;
    }

    @Test
    void fetchSigstoreBundlesReturnsEveryAttachedBundleInStableOrder() throws Exception {
        byte[] content = {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00};
        String ref = pushTestWasm("multi-bundle", content);

        InlayClient client = InlayClient.builder().insecure().noCache().build();
        String digest = client.getDigest(ref);

        byte[] first = "{\"bundle\":\"one\"}".getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"bundle\":\"two\"}".getBytes(StandardCharsets.UTF_8);
        attachSigstoreBundle(ref, digest, first);
        attachSigstoreBundle(ref, digest, second);

        List<Path> bundles = client.fetchSigstoreBundles(ref, digest, tempDir.resolve("bundles"));
        assertEquals(2, bundles.size());

        List<String> contents = new ArrayList<>();
        for (Path bundle : bundles) {
            contents.add(Files.readString(bundle));
        }
        assertTrue(contents.contains(new String(first, StandardCharsets.UTF_8)));
        assertTrue(contents.contains(new String(second, StandardCharsets.UTF_8)));

        List<Path> again = client.fetchSigstoreBundles(ref, digest, tempDir.resolve("bundles2"));
        List<String> againContents = new ArrayList<>();
        for (Path bundle : again) {
            againContents.add(Files.readString(bundle));
        }
        assertEquals(contents, againContents);
    }

    private void attachSigstoreBundle(String ref, String subjectDigest, byte[] bundle) {
        Registry registry = insecureRegistry();
        ContainerRef containerRef = ContainerRef.parse(ref);

        Layer layer =
                registry.pushBlob(containerRef, bundle)
                        .withMediaType(Const.SIGSTORE_BUNDLE_MEDIA_TYPE);
        Descriptor subject = registry.getDescriptor(containerRef);

        Manifest manifest =
                Manifest.empty()
                        .withArtifactType(ArtifactType.from(Const.SIGSTORE_BUNDLE_MEDIA_TYPE))
                        .withLayers(List.of(layer))
                        .withSubject(
                                Subject.of(
                                        subject.getMediaType(), subjectDigest, subject.getSize()));

        registry.pushManifest(
                containerRef.withDigest(ManifestDescriptor.of(manifest).getDigest()), manifest);
    }

    @Test
    void fetchSigstoreBundlesReturnsEmptyWhenNoBundleExists() throws Exception {
        byte[] content = {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00};
        String ref = pushTestWasm("no-sig-multi", content);

        InlayClient client = InlayClient.builder().insecure().noCache().build();
        String digest = client.getDigest(ref);

        assertTrue(client.fetchSigstoreBundles(ref, digest, tempDir.resolve("bundles")).isEmpty());
    }

    @Test
    void fetchSigstoreBundleReturnsNullWhenNoBundleExists() throws Exception {
        byte[] content = {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00};
        String ref = pushTestWasm("no-sig", content);

        InlayClient client = InlayClient.builder().insecure().noCache().build();
        String digest = client.getDigest(ref);

        Path bundlePath = tempDir.resolve("bundle.sigstore.json");
        Path result = client.fetchSigstoreBundle(ref, digest, bundlePath);
        assertNull(result);
    }
}
