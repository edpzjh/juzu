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

package org.juzu.request;

import org.juzu.impl.application.ApplicationException;
import org.juzu.metadata.ApplicationDescriptor;
import org.juzu.template.Template;
import org.juzu.template.TemplateRenderContext;

import java.util.Locale;
import java.util.Map;

/** @author <a href="mailto:julien.viet@exoplatform.com">Julien Viet</a> */
public abstract class ApplicationContext
{

   public ApplicationContext()
   {
   }

   public abstract ApplicationDescriptor getDescriptor();

   public abstract Object resolveBean(String name) throws ApplicationException;

   public abstract TemplateRenderContext render(Template template, Map<String, ?> parameters, Locale locale);

}
