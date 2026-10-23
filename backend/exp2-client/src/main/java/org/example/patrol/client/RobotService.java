package org.example.patrol.client;

import org.example.patrol.client.RobotModels.*;

public interface RobotService {
    RobotView robot();

    RobotStatusView status();

    ControlResultView control(RobotControlCommand command, long actor);
}
