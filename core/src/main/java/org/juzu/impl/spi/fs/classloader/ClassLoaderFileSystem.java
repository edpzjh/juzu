/*
 * Copyright (C) 2011 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.juzu.impl.spi.fs.classloader;

import org.juzu.impl.spi.fs.SimpleFileSystem;
import org.juzu.impl.utils.Content;
import org.juzu.impl.utils.Tools;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/** @author <a href="mailto:julien.viet@exoplatform.com">Julien Viet</a> */
public class ClassLoaderFileSystem extends SimpleFileSystem<String>
{

   /** . */
   private final ClassLoader classLoader;

   /** . */
   private Map<URL, String[]> cache;

   public ClassLoaderFileSystem(ClassLoader classLoader)
   {
      if (classLoader == null)
      {
         throw new NullPointerException("No null class loader accepted");
      }
      this.classLoader = classLoader;
      this.cache = new HashMap<URL, String[]>();
   }

   public ClassLoader getClassLoader()
   {
      return classLoader;
   }

   @Override
   public String getName(String path) throws IOException
   {
      if ("".equals(path) || "/".equals(path))
      {
         return "";
      }
      else if (isDir(path))
      {
         int index = path.lastIndexOf('/', path.length() - 2);
         if (index == -1)
         {
            index = 0;
         }
         return path.substring(index, path.length() - 1);
      }
      else
      {
         int index = path.lastIndexOf('/');
         return index == -1 ? path : path.substring(index + 1);
      }
   }

   @Override
   public String getPath(Iterable<String> names) throws IOException
   {
      StringBuilder sb = new StringBuilder();
      boolean foo = true;
      for (String name : names)
      {
         if (foo)
         {
            foo = false;
         }
         else
         {
            sb.append('/');
         }
         sb.append(name);
      }

      //
      sb.append('/');
      String path = sb.toString();
      URL url = classLoader.getResource(path);
      if (url == null)
      {
         path = sb.substring(0, sb.length() - 1);
         url = classLoader.getResource(path);
      }
      return url != null ? path : null;
   }

   @Override
   public void packageOf(String path, Collection<String> to) throws IOException
   {
      for (int prev = 0,next = path.indexOf('/');next != -1;next = path.indexOf('/', prev))
      {
         to.add(path.substring(prev, next));
         prev = next + 1;
      }
   }

   @Override
   public Iterator<String> getChildren(String dir) throws IOException
   {
      List<String> ret = new ArrayList<String>();
      Enumeration<URL> e = classLoader.getResources(dir);
      while (e.hasMoreElements())
      {
         URL url = e.nextElement();
         String protocol = url.getProtocol();
         if (protocol.equals("jar"))
         {
            String path = url.getPath();
            int pos = path.indexOf("!/");
            URL url2 = new URL(path.substring(0, pos));
            String[] entries = cache.get(url2);
            if (entries == null)
            {
               JarInputStream in = new JarInputStream(url2.openStream());
               try
               {
                  ArrayList<String> tmp = new ArrayList<String>();
                  for (JarEntry jarEntry = in.getNextJarEntry();jarEntry != null;jarEntry = in.getNextJarEntry())
                  {
                     tmp.add(jarEntry.getName());
                  }
                  entries = tmp.toArray(new String[tmp.size()]);
                  cache.put(url2, entries);
               }
               finally
               {
                  Tools.safeClose(in);
               }
            }
            for (String entry : entries)
            {
               if (entry.length() > dir.length() + 1 &&
                  entry.startsWith(dir) &&
                  entry.indexOf('/', dir.length() + 1) == -1)
               {
                  ret.add(entry);
               }
            }
         }
         else
         {
            throw new UnsupportedOperationException("todo");
         }
      }
      return ret.iterator();
   }

   @Override
   public boolean isDir(String path) throws IOException
   {
      return path.length() == 0 || path.endsWith("/");
   }

   @Override
   public boolean isFile(String path) throws IOException
   {
      return !isDir(path);
   }

   @Override
   public Content getContent(String file) throws IOException
   {
      URL url = classLoader.getResource(file);
      if (url == null)
      {
         throw new UnsupportedOperationException("handle me correctly");
      }

      //
      URLConnection conn = url.openConnection();
      long lastModified = conn.getLastModified();
      byte[] bytes = Tools.bytes(conn.getInputStream());
      return new Content(lastModified,  bytes, Charset.defaultCharset());
   }

   @Override
   public File getFile(String path) throws IOException
   {
      throw new UnsupportedOperationException();
   }
}
