package net.jxta.module;

import net.jxta.impl.modulemanager.ModuleEvent;

public interface IModuleBuilderListener<T extends Object> {

	public enum BuildEvents{
		INIT,
		BUILD;
	}
	
	/**
	 * Notify listeners top a built module
	 * @param event
	 */
	public void notifyModuleBuilt( ModuleEvent<T> event );
}