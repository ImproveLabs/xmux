import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins { id("com.vanniktech.maven.publish") }

extensions.configure<MavenPublishBaseExtension> {
  // AGP 8.11's bundled Dokka cannot read Java 17 sealed classes; ship sources without Javadocs.
  pluginManager.withPlugin("com.android.library") {
    configure(AndroidSingleVariantLibrary(publishJavadocJar = false))
  }
  if (project.hasProperty("signingInMemoryKey")) signAllPublications()
  coordinates(project.group.toString(), project.name, project.version.toString())
  pom {
    name.set(project.name)
    description.set("xmux: cmux on VR (${project.name})")
    url.set("https://github.com/ImproveLabs/xmux")
    licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
    developers { developer { id.set("leonsilicon"); name.set("Leon Si") } }
    scm {
      url.set("https://github.com/ImproveLabs/xmux")
      connection.set("scm:git:https://github.com/ImproveLabs/xmux.git")
      developerConnection.set("scm:git:ssh://git@github.com/ImproveLabs/xmux.git")
    }
  }
}
