/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.kernel.util;

import java.io.Serializable;

import java.util.Map;

/**
 * @author Magdalena Jedraszak
 */
public class RegionUpdateInfo implements Serializable {

	public RegionUpdateInfo() {
	}

	public RegionUpdateInfo(
		String externalReferenceCode, boolean active, String name,
		double position, String regionCode, Map<String, String> titleMap) {

		_externalReferenceCode = externalReferenceCode;
		_active = active;
		_name = name;
		_position = position;
		_regionCode = regionCode;
		_titleMap = titleMap;
	}

	public String getExternalReferenceCode() {
		return _externalReferenceCode;
	}

	public String getName() {
		return _name;
	}

	public double getPosition() {
		return _position;
	}

	public String getRegionCode() {
		return _regionCode;
	}

	public Map<String, String> getTitleMap() {
		return _titleMap;
	}

	public boolean isActive() {
		return _active;
	}

	public void setActive(boolean active) {
		_active = active;
	}

	public void setExternalReferenceCode(String externalReferenceCode) {
		_externalReferenceCode = externalReferenceCode;
	}

	public void setName(String name) {
		_name = name;
	}

	public void setPosition(double position) {
		_position = position;
	}

	public void setRegionCode(String regionCode) {
		_regionCode = regionCode;
	}

	public void setTitleMap(Map<String, String> titleMap) {
		_titleMap = titleMap;
	}

	private static final long serialVersionUID = 1L;

	private boolean _active;
	private String _externalReferenceCode;
	private String _name;
	private double _position;
	private String _regionCode;
	private Map<String, String> _titleMap;

}
