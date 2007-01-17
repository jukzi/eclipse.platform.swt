/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.swt.dnd;

import org.eclipse.swt.widgets.*;

/**
 * This class provides default implementations to display a source image
 * when a drag is initiated from a <code>Tree</code>.
 * 
 * <p>Classes that wish to provide their own source image for a <code>Table</code> can
 * extend <code>DragSourceAdapter</code> class and override the <code>DragSourceAdapter.dragStart</code>
 * method and set the field <code>DragSourceEvent.image</code> with their own image.</p>
 *
 * Subclasses that override any methods of this class must call the corresponding
 * <code>super</code> method to get the default drag under effect implementation.
 *
 * @see DragSourceAdapter
 * @see DragSourceEvent
 * 
 * @since 3.3
 */
public class TreeDragSourceEffect extends DragSourceEffect {
	/**
	 * Creates a new <code>TreeDragSourceEffect</code> to handle drag effect 
	 * from the specified <code>Tree</code>.
	 *
	 * @param table the <code>Tree</code> that the user clicks on to initiate the drag
	 **/
	public TreeDragSourceEffect(Tree tree) {
		super(tree);
	}
}
