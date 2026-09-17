package io.roastedroot.inlay.maven;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in check against a real signed artifact; keyless signing needs interactive OIDC, so this
 * cannot run in CI. Set inlay.e2e.imageRef, inlay.e2e.issuer and inlay.e2e.identity to enable.
 */
@EnabledIfSystemProperty(named = "inlay.e2e.imageRef", matches = ".+")
class FetchMojoSignedE2EIT {

    @TempDir Path tempDir;

    @Test
    void fetchesAndVerifiesASignedArtifact() throws Exception {
        File outputFile = tempDir.resolve("out/module.wasm").toFile();

        ModuleConfig module = new ModuleConfig();
        module.setImageRef(System.getProperty("inlay.e2e.imageRef"));
        module.setOutputFile(outputFile);
        module.setSigstoreIssuer(System.getProperty("inlay.e2e.issuer"));
        module.setSigstoreIdentity(System.getProperty("inlay.e2e.identity"));

        FetchMojo mojo = new FetchMojo();
        FetchMojoTest.setField(mojo, "modules", List.of(module));
        FetchMojoTest.setField(mojo, "lockFile", tempDir.resolve("wkg.lock").toFile());
        FetchMojoTest.setField(mojo, "update", false);
        FetchMojoTest.setField(mojo, "noCache", true);
        FetchMojoTest.setField(mojo, "settings", new Settings());

        MavenProject project = new MavenProject();
        Build build = new Build();
        build.setDirectory(tempDir.resolve("target").toString());
        project.setBuild(build);
        FetchMojoTest.setField(mojo, "project", project);

        mojo.execute();

        assertTrue(outputFile.exists(), "verified artifact was not written");
    }
}
