/*
 * ATLauncher - https://github.com/ATLauncher/ATLauncher
 * Copyright (C) 2013-2021 ATLauncher
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.atlauncher.gui.panels.packbrowser;

import java.awt.BorderLayout;
import java.awt.Color;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import com.atlauncher.utils.Utils;

public class PacksBrowserTabTitlePanel extends JPanel {
    public PacksBrowserTabTitlePanel(String platform) {
        setLayout(new BorderLayout());
        setBackground(new Color(0, 0, 0, 1));
        add(new JLabel(
                Utils.getIconImage(String.format("/assets/image/modpack-platform/%s.png", platform.toLowerCase()))),
                BorderLayout.CENTER);

        JLabel title = new JLabel(platform);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        add(title, BorderLayout.SOUTH);
    }
}
