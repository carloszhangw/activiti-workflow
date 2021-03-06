package com.activiti.common.utils;

import com.activiti.common.kafka.MailProducer;
import com.activiti.mapper.UserMapper;
import com.activiti.pojo.email.EmailDto;
import com.activiti.pojo.schedule.ScheduleDto;
import com.activiti.pojo.user.StudentWorkInfo;
import com.activiti.service.ScheduleService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 相关操作
 */
@Component
public class ActivitiHelper {
    private final Logger logger = LoggerFactory.getLogger(ActivitiHelper.class);

    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private MailProducer mailProducer;

    /**
     * activiti分配作业
     *
     * @param jsonObject
     */
    @SuppressWarnings("unchecked")
    public void distributeTask(JSONObject jsonObject) {
        List<String> emailList = (List<String>) jsonObject.get("emailList");
        String courseCode = (String) jsonObject.get("courseCode");
        Collections.shuffle(emailList);
        logger.info("启动作业分配流程");
        ScheduleDto scheduleDto = scheduleService.selectScheduleTime(courseCode);
        int judgeTimes = scheduleDto.getJudgeTimes();//每份作业被批改次数（默认为4）
        int countWork = emailList.size();
        //为每一个人分配要批改的作业
        emailList.forEach(email -> {
            int studentId = emailList.indexOf(email);
            JSONArray jsonArray = new JSONArray();
            for (int i = 1; i <= judgeTimes; i++) {
                int id = studentId + i;
                int result = id >= countWork ? id - countWork : id;
                jsonArray.add(emailList.get(result));
            }
            //设置流程变量
            Map<String, Object> variables = new HashMap<>();
            variables.put("courseCode", courseCode);
            variables.put("assignee", email);
            variables.put("judgeEmailList", jsonArray.toJSONString());
            variables.put("timeout", scheduleDto.getTimeout());
            String businessKey = "assessment";
            //启动流程
            ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("assessmentWorkFlow", businessKey, variables);
            logger.info("用户" + email + ">>>>>>>>>>启动流程：" + processInstance.getId());
            userMapper.updateDistributeStatus(courseCode, email);
            logger.info("更新用户" + email + "分配状态");
        });
    }

    /**
     * 查询需要评论的作业
     *
     * @param assignee
     * @param courseCode
     * @return
     */
    public JSONArray selectWorkListToJudge(String assignee, String courseCode) {
        List<Task> list = taskService
                .createTaskQuery()//
                .taskAssignee(assignee)//个人任务的查询
                .list();
        JSONArray jsonArray = new JSONArray();
        list.forEach(task -> {
            String taskId = task.getId();
            if (courseCode.equals(taskService.getVariable(taskId, "courseCode"))) {
                JSONArray.parseArray(taskService.getVariable(taskId, "judgeEmailList").toString()).forEach(a -> {
                    jsonArray.add(a.toString());
                });
            }
        });
        if (taskService.createTaskQuery().taskAssignee(assignee).list() == null || taskService.createTaskQuery().taskAssignee(assignee).list().size() == 0) {
            logger.info(">>>>>>>>>>>>>>>>>>>>我已经没有任务了");
        }
        return jsonArray;
    }

    /**
     * 没有参与互评邮件提醒
     *
     * @param execution
     */
    public void emailAlert(DelegateExecution execution) {
        String emailList = (String) execution.getVariable("emailAlertList");
        JSONArray jsonArray = JSONArray.parseArray(emailList);
        if (null == jsonArray)
            return;
        logger.info("开始向这些人发邮件" + jsonArray);
        jsonArray.forEach(email -> {

        });
    }

    /**
     * 结束流程
     *
     * @param assignee
     * @param courseCode
     */
    public void completeTask(String assignee, String courseCode) {
        taskService
                .createTaskQuery()//
                .taskAssignee(assignee)//个人任务的查询
                .list().forEach(task -> {
            String taskId = task.getId();
            if (courseCode.equals(taskService.getVariable(taskId, "courseCode"))) {
                taskService.complete(taskId);
            }
        });
    }

    /**
     * 启动教师审查流程
     *
     * @param studentWorkInfo
     */
    public void startTeacherVerify(StudentWorkInfo studentWorkInfo) {
        String courseCode = studentWorkInfo.getCourseCode();
        String email = studentWorkInfo.getEmailAddress();
        Map<String, Object> variables = new HashMap<>();
        variables.put("studentWorkInfo", ((JSONObject) JSONObject.toJSON(studentWorkInfo)).toJSONString());
        String businessKey = "verifyTaskBusinessKey";
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("verifyTask", businessKey, variables);
        logger.info("用户" + email + ">>>>>>>>>>启动教师审核流程：" + processInstance.getId());
        userMapper.askToVerify(courseCode, email);
    }

    /**
     * 查询教师所有任务
     */
    public JSONArray selectAllTeacherTask() {
        List<Task> list = taskService
                .createTaskQuery()//
                .taskAssignee("teacher")//个人任务的查询
                .list();
        JSONArray jsonArray = new JSONArray();
        list.forEach(task -> {
            String taskId = task.getId();
            JSONObject studentWorkInfo = JSONObject.parseObject(taskService.getVariable(taskId, "studentWorkInfo").toString());
            studentWorkInfo.put("taskId", taskId);
            jsonArray.add(studentWorkInfo);
        });
        return jsonArray;
    }

    /**
     * 结束任务
     *
     * @param taskId
     */
    public void finishTeacherVerifyTask(String taskId) {
        taskService.complete(taskId);
    }
}
