/**
 * 
 */
package dacapo;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLStreamHandlerFactory;
import java.util.ArrayList;
import java.util.List;

import dacapo.parser.Config;

/**
 * @author Robin Garner
 * @author Steve Blackburn
 *
 */
public class DacapoClassLoader extends URLClassLoader {
  
  /**
   * Factory method to create the class loader to be used for each invocation of this benchmark
   *
   * @param config The config file, which contains information about the jars this benchmark depends on
   * @param scratch The scratch directory (in which the jars will be located)
   * @return The class loader in which this benchmark's iterations should execute.
   * @throws Exception
   */
  public static DacapoClassLoader create(Config config, File scratch) {
    DacapoClassLoader rtn = null;
    try {
      URL[] urls = getJars(config, scratch);
      if (Benchmark.isVerbose()) {
        System.out.println("Benchmark classpath:");
        for (URL url : urls) {
          System.out.println("  "+url.toString());
        }
      }
      rtn = new DacapoClassLoader(urls, Thread.currentThread().getContextClassLoader());
    } catch (Exception e) {
      System.err.println("Unable to create loader for "+config.name+":");
      e.printStackTrace();
      System.exit(-1);
    }
    return rtn;
  }

  /**
   * @param urls
   */
  public DacapoClassLoader(URL[] urls) {
    super(urls);
  }

  /**
   * @param urls
   * @param parent
   */
  public DacapoClassLoader(URL[] urls, ClassLoader parent) {
    super(urls, parent);
  }

  /**
   * @param urls
   * @param parent
   * @param factory
   */
  public DacapoClassLoader(URL[] urls, ClassLoader parent,
      URLStreamHandlerFactory factory) {
    super(urls, parent, factory);
  }

  /**
   * Get a list of jars (if any) which should be in the classpath for this benchmark
   *
   * @param config The config file for this benchmark, which lists the jars
   * @param scratch The scratch directory, in which the jars will be located
   * @return An array of URLs, one URL for each jar
   * @throws MalformedURLException
   */
  private static URL[] getJars(Config config, File scratch) throws MalformedURLException {
    List<URL> jars = new ArrayList<URL>();
    if (config.jar != null) {
      File jar = new File(scratch, config.jar);
      jars.add(jar.toURL());
    }
    if (config.libs != null) {
      for (int i = 0; i < config.libs.length; i++) {
        File jar = new File(scratch, config.libs[i]);
        jars.add(jar.toURL());
      }
    }
    return jars.toArray(new URL[jars.size()]);
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    return super.findClass(name);
  }
}