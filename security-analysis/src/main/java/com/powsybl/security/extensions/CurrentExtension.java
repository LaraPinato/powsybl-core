/**
 * Copyright (c) 2018, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.security.extensions;

import com.powsybl.commons.extensions.Extension;
import com.powsybl.security.LimitViolation;

/**
 * @author Mathieu Bague <mathieu.bague@rte-france.com>
 */
public class CurrentExtension implements Extension<LimitViolation> {

    private LimitViolation limitViolation;

    private final float preContingencyValue;

    public CurrentExtension(float value) {
        this.preContingencyValue = checkValue(value);
    }

    @Override
    public String getName() {
        return "Current";
    }

    @Override
    public LimitViolation getExtendable() {
        return limitViolation;
    }

    @Override
    public void setExtendable(LimitViolation limitViolation) {
        this.limitViolation = limitViolation;
    }

    public float getPreContingencyValue() {
        return preContingencyValue;
    }

    private static float checkValue(float value) {
        if (Float.isNaN(value)) {
            throw new IllegalArgumentException("Value is undefined");
        }

        return value;
    }
}