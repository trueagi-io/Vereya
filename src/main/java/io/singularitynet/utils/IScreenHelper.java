package io.singularitynet.utils;

import io.singularitynet.utils.ScreenHelper.TextCategory;

public interface IScreenHelper {

	void clearFragment(String infoReserveStatus);

    public void addFragment(String text, TextCategory category, String handle);

}
