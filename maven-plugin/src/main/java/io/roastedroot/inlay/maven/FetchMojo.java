package io.roastedroot.inlay.maven;

import dev.sigstore.KeylessVerificationException;
import dev.sigstore.KeylessVerifier;
import dev.sigstore.VerificationOptions;
import dev.sigstore.VerificationOptions.CertificateMatcher;
import dev.sigstore.bundle.Bundle;
import dev.sigstore.bundle.BundleParseException;
import dev.sigstore.strings.RegexSyntaxException;
import dev.sigstore.strings.StringMatcher;
import dev.sigstore.trustroot.SigstoreConfigurationException;
import io.roastedroot.inlay.InlayClient;
import io.roastedroot.inlay.InlayException;
import io.roastedroot.inlay.LockFile;
import io.roastedroot.inlay.LockedPackage;
import io.roastedroot.inlay.WkgParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;

@Mojo(name = "fetch", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class FetchMojo extends AbstractMojo {

    private static final String SIGSTORE_BUNDLE_MEDIA_TYPE =
            "application/vnd.dev.sigstore.bundle.v0.3+json";

    private static final String SIGNING_INSTRUCTIONS =
            "Sign the artifact with cosign v3+ (on v2.x add --new-bundle-format):\n"
                    + "  cosign sign-blob --yes --bundle <file>.wasm.sigstore.json <file>.wasm\n"
                    + "  oras attach --artifact-type "
                    + SIGSTORE_BUNDLE_MEDIA_TYPE
                    + " <ref> <file>.wasm.sigstore.json:"
                    + SIGSTORE_BUNDLE_MEDIA_TYPE;

    @Parameter(required = true)
    private List<ModuleConfig> modules;

    @Parameter(
            required = true,
            defaultValue = "${project.basedir}/wkg.lock",
            property = "inlay.lockFile")
    private File lockFile;

    @Parameter(defaultValue = "false", property = "inlay.update")
    private boolean update;

    @Parameter(defaultValue = "false", property = "inlay.noCache")
    private boolean noCache;

    @Parameter(defaultValue = "false", property = "inlay.insecure")
    private boolean insecure;

    @Parameter(property = "inlay.configFile")
    private File configFile;

    @Parameter(defaultValue = "false", property = "inlay.skip")
    private boolean skip;

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    @Parameter(property = "settings", required = true, readonly = true)
    private Settings settings;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping inlay:fetch (inlay.skip=true)");
            return;
        }
        try (WkgParser parser = new WkgParser()) {
            LockFile lock;
            try {
                lock = LockFile.read(parser, lockFile.toPath());
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to read lock file: " + lockFile, e);
            }

            boolean lockChanged = false;

            for (ModuleConfig module : modules) {
                boolean changed = fetchModule(module, lock, parser);
                lockChanged = lockChanged || changed;
            }

            if (lockChanged) {
                try {
                    lock.write(lockFile.toPath());
                    getLog().info("Updated lock file: " + lockFile);
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to write lock file: " + lockFile, e);
                }
            }
        }
    }

    private boolean fetchModule(ModuleConfig module, LockFile lock, WkgParser parser)
            throws MojoExecutionException {
        String imageRef = module.getImageRef();

        if ((imageRef == null || imageRef.isEmpty()) && module.getPackageRef() != null) {
            imageRef = resolvePackageRef(module.getPackageRef(), lock);
        }

        if (imageRef == null || imageRef.isEmpty()) {
            throw new MojoExecutionException(
                    "Either imageRef or packageRef must be specified for each module");
        }

        if (!imageRef.contains("/")) {
            throw new MojoExecutionException(
                    "Invalid imageRef (expected registry/repository[:tag]): " + imageRef);
        }

        File outputFile = module.getOutputFile();
        if (outputFile == null) {
            outputFile = defaultOutputFile(imageRef);
        }

        Path outputPath = outputFile.toPath();

        LockedPackage locked = lock.findByImageRef(imageRef);

        if (locked != null && !update && Files.exists(outputPath)) {
            getLog().info("Already fetched (locked): " + imageRef);
            return false;
        }

        getLog().info("Fetching " + imageRef + " -> " + outputPath);

        try {
            InlayClient client = createClient(imageRef);

            String resolvedDigest = client.getDigest(imageRef);

            if (locked != null && !update && !locked.getDigest().equals(resolvedDigest)) {
                throw new MojoExecutionException(
                        "Digest mismatch for "
                                + imageRef
                                + ": lock file has "
                                + locked.getDigest()
                                + " but registry resolved "
                                + resolvedDigest
                                + ". Run with -Dinlay.update to accept the new digest.");
            }

            boolean needsVerification =
                    module.getSigstoreIssuer() != null
                            || module.getSigstoreIdentity() != null
                            || module.getSigstoreIssuerRegex() != null
                            || module.getSigstoreIdentityRegex() != null;

            if (needsVerification) {
                Files.createDirectories(outputPath.getParent());
                Path tempPath = null;
                Path bundleDir = null;
                try {
                    tempPath = Files.createTempFile(outputPath.getParent(), "inlay-", ".wasm.tmp");
                    bundleDir = Files.createTempDirectory(outputPath.getParent(), "inlay-bundles-");
                    client.pullByDigest(imageRef, resolvedDigest, tempPath);
                    verifySigstore(client, imageRef, resolvedDigest, tempPath, bundleDir, module);
                    Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
                    tempPath = null;
                } finally {
                    if (tempPath != null) {
                        Files.deleteIfExists(tempPath);
                    }
                    if (bundleDir != null) {
                        deleteRecursive(bundleDir);
                    }
                }
            } else {
                client.pullByDigest(imageRef, resolvedDigest, outputPath);
            }

            getLog().info("Fetched " + imageRef + " -> " + outputPath);

            lock.addOrUpdate(imageRef, resolvedDigest);
            return true;
        } catch (InlayException e) {
            throw new MojoExecutionException(
                    "Failed to fetch " + imageRef + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to fetch " + imageRef + ": " + e.getMessage(), e);
        }
    }

    private void verifySigstore(
            InlayClient client,
            String imageRef,
            String digest,
            Path artifactPath,
            Path bundleDir,
            ModuleConfig module)
            throws MojoExecutionException {
        getLog().info("Fetching sigstore bundle for " + imageRef);
        List<Path> bundlePaths = client.fetchSigstoreBundles(imageRef, digest, bundleDir);

        if (bundlePaths.isEmpty()) {
            throw new MojoExecutionException(
                    "No sigstore bundle found for " + imageRef + ". " + SIGNING_INSTRUCTIONS);
        }

        getLog().info("Verifying signature for " + imageRef);

        // Any one bundle verifying against the configured identity is enough.
        MojoExecutionException firstRealFailure = null;
        MojoExecutionException firstUnsupported = null;

        for (Path bundlePath : bundlePaths) {
            try {
                verifyBundle(bundlePath, digest, artifactPath, module);
                getLog().info("Signature verified for " + imageRef);
                return;
            } catch (UnsupportedDsseBundleException e) {
                if (firstUnsupported == null) {
                    firstUnsupported = new MojoExecutionException(e.getMessage(), e);
                }
            } catch (MojoExecutionException e) {
                if (firstRealFailure == null) {
                    firstRealFailure = e;
                }
            }
        }

        throw firstRealFailure != null ? firstRealFailure : firstUnsupported;
    }

    private void verifyBundle(
            Path bundlePath, String digest, Path artifactPath, ModuleConfig module)
            throws MojoExecutionException, UnsupportedDsseBundleException {
        try {
            Bundle bundle = Bundle.from(bundlePath, StandardCharsets.UTF_8);

            var optionsBuilder = VerificationOptions.builder();
            var matcherBuilder = CertificateMatcher.fulcio();
            StringMatcher identity =
                    matcherFor(
                            "Identity",
                            module.getSigstoreIdentity(),
                            module.getSigstoreIdentityRegex());
            if (identity != null) {
                matcherBuilder.subjectAlternativeName(identity);
            }
            StringMatcher issuer =
                    matcherFor(
                            "Issuer", module.getSigstoreIssuer(), module.getSigstoreIssuerRegex());
            if (issuer != null) {
                matcherBuilder.issuer(issuer);
            }
            optionsBuilder.addCertificateMatchers(matcherBuilder.build());

            KeylessVerifier verifier = KeylessVerifier.builder().sigstorePublicDefaults().build();

            byte[] artifactDigest = selectArtifactDigest(bundle, digest);
            if (artifactDigest != null) {
                verifier.verify(artifactDigest, bundle, optionsBuilder.build());
            } else {
                verifier.verify(artifactPath, bundle, optionsBuilder.build());
            }
        } catch (KeylessVerificationException e) {
            if (isMissingSubjectName(e)) {
                throw new UnsupportedDsseBundleException();
            }
            throw new MojoExecutionException(
                    "Signature verification failed for " + artifactPath + ": " + e.getMessage(), e);
        } catch (BundleParseException
                | IOException
                | GeneralSecurityException
                | SigstoreConfigurationException e) {
            throw new MojoExecutionException(
                    "Signature verification failed for " + artifactPath + ": " + e.getMessage(), e);
        }
    }

    /** The exact form compares with String.equals, so globs need the regex form. */
    static StringMatcher matcherFor(String field, String exact, String regex)
            throws MojoExecutionException {
        if (exact != null && regex != null) {
            throw new MojoExecutionException(
                    "Configure either sigstore"
                            + field
                            + " or sigstore"
                            + field
                            + "Regex, not"
                            + " both");
        }
        if (exact != null) {
            return StringMatcher.string(exact);
        }
        if (regex == null) {
            return null;
        }
        try {
            return StringMatcher.regex(regex);
        } catch (RegexSyntaxException e) {
            throw new MojoExecutionException(
                    "Invalid sigstore" + field + "Regex: " + regex + ": " + e.getMessage(), e);
        }
    }

    /**
     * The digest a bundle's signature covers: the OCI manifest digest for `cosign sign`, or null
     * for `cosign sign-blob`, where sigstore-java hashes the artifact itself.
     */
    static byte[] selectArtifactDigest(Bundle bundle, String manifestDigest)
            throws MojoExecutionException {
        if (!bundle.getDsseEnvelope().isPresent()) {
            return null;
        }

        int sep = manifestDigest.indexOf(':');
        if (sep < 0 || !"sha256".equals(manifestDigest.substring(0, sep))) {
            throw new MojoExecutionException(
                    "Unsupported manifest digest algorithm: "
                            + manifestDigest
                            + " (DSSE bundles are signed over a sha256 digest)");
        }
        return hexToBytes(manifestDigest.substring(sep + 1));
    }

    static byte[] hexToBytes(String hex) throws MojoExecutionException {
        if (hex.length() % 2 != 0) {
            throw new MojoExecutionException("Invalid hex digest (odd length): " + hex);
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new MojoExecutionException("Invalid hex digest: " + hex);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Delete once sigstore-java makes InTotoPayload.Subject#getName @Nullable. */
    static boolean isMissingSubjectName(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("Cannot build Subject")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static final class UnsupportedDsseBundleException extends Exception {
        UnsupportedDsseBundleException() {
            super(
                    "This artifact is signed with `cosign sign`, which produces a DSSE bundle whose"
                        + " in-toto subject has no \"name\" field. sigstore-java (through 2.2.0)"
                        + " requires that field, so the bundle cannot be parsed and the signature"
                        + " cannot be checked. Upstream fix needed: InTotoPayload.Subject#getName"
                        + " must be @Nullable (https://github.com/sigstore/sigstore-java). "
                            + SIGNING_INSTRUCTIONS);
        }
    }

    private void deleteRecursive(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException e) {
                                    getLog().debug("Failed to delete: " + p, e);
                                }
                            });
        } catch (IOException e) {
            getLog().debug("Failed to clean up directory: " + dir, e);
        }
    }

    private String resolvePackageRef(String packageRef, LockFile lock)
            throws MojoExecutionException {
        String namespace = packageRef;
        String version = null;
        int atIndex = packageRef.indexOf('@');
        if (atIndex >= 0) {
            namespace = packageRef.substring(0, atIndex);
            version = packageRef.substring(atIndex + 1);
        }

        int colonIndex = namespace.indexOf(':');
        if (colonIndex < 0) {
            throw new MojoExecutionException(
                    "Invalid packageRef (expected namespace:name[@version]): " + packageRef);
        }

        String ns = namespace.substring(0, colonIndex);
        String name = namespace.substring(colonIndex + 1);

        String configToml = loadConfigToml();
        String registry = lock.resolveNamespace(configToml, ns);
        if (registry == null) {
            throw new MojoExecutionException(
                    "No registry found for namespace '" + ns + "' in config.toml");
        }

        String ref = registry + "/" + ns + "/" + name;
        if (version != null) {
            ref = ref + ":" + version;
        }

        getLog().info("Resolved " + packageRef + " -> " + ref);
        return ref;
    }

    private String loadConfigToml() throws MojoExecutionException {
        if (configFile != null && configFile.exists()) {
            try {
                return Files.readString(configFile.toPath());
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to read config file: " + configFile, e);
            }
        }

        Path defaultConfig = defaultConfigPath();
        if (defaultConfig != null && Files.exists(defaultConfig)) {
            try {
                return Files.readString(defaultConfig);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to read config file: " + defaultConfig, e);
            }
        }

        return "";
    }

    private static Path defaultConfigPath() {
        String xdgConfig = System.getenv("XDG_CONFIG_HOME");
        if (xdgConfig != null && !xdgConfig.isEmpty()) {
            return Path.of(xdgConfig, "wasm-pkg", "config.toml");
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            return Path.of(home, ".config", "wasm-pkg", "config.toml");
        }
        return null;
    }

    private File defaultOutputFile(String imageRef) {
        String filename = imageRef.substring(imageRef.lastIndexOf('/') + 1);
        int tagSep = filename.indexOf(':');
        if (tagSep > 0) {
            filename = filename.substring(0, tagSep);
        }
        int digestSep = filename.indexOf('@');
        if (digestSep > 0) {
            filename = filename.substring(0, digestSep);
        }
        filename = filename + ".wasm";
        return new File(project.getBuild().getDirectory() + "/wasm/" + filename);
    }

    private InlayClient createClient(String imageRef) throws MojoExecutionException {
        int slashIndex = imageRef.indexOf('/');
        if (slashIndex < 0) {
            throw new MojoExecutionException(
                    "Invalid imageRef (expected registry/repository[:tag]): " + imageRef);
        }
        String registryHost = imageRef.substring(0, slashIndex);

        InlayClient.Builder builder = InlayClient.builder();
        if (noCache) {
            builder.noCache();
        }
        if (insecure) {
            builder.insecure();
        }

        Server server = settings.getServer(registryHost);
        if (server != null && server.getUsername() != null && server.getPassword() != null) {
            getLog().debug("Using credentials from settings.xml for " + registryHost);
            builder.withCredentials(server.getUsername(), server.getPassword());
        }

        return builder.build();
    }
}
