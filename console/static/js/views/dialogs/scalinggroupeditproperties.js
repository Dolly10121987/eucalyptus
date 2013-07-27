define([
  './eucadialogview',
  'models/scalinggrp', 
  'views/newscalinggroup/page1',
  'views/newscalinggroup/page2',
  'views/newscalinggroup/page3',
  'text!./scalinggroupeditproperties.html'
], function(EucaDialog, ScalingGroup, tab1,tab2,tab3, tpl) {
  return EucaDialog.extend({
    initialize: function(options) {
      var self = this;
      this.template = tpl;

      this.scope = new Backbone.Model({
        cancelButton: {
          id: 'button-dialog-editscalinggroup-cancel',
          click: function() {
            self.close();
          }
        },

        createButton: {
          id: 'button-dialog-editscalinggroup-save',
          click: function() {
            self.close();
          }
        },

        availabilityZones: new Backbone.Collection(),
        loadBalancers: new Backbone.Collection(),
        alarms: new Backbone.Collection(),
        policies: new Backbone.Collection(),
        toggletest: new Backbone.Model({value: false}),
        scalingGroup: new ScalingGroup({
                min_size: 0,
                desired_capacity: 0,
                max_size: 0,
                launch_config_name: options.launchconfig ? options.launchconfig : null,
                show_lc_selector: options.launchconfig ? false : true
        }),
        change: function(e) {
            setTimeout(function() { $(e.target).change(); }, 0);
        }
      });

      //init from options
      if(options && options.model && options.model.length > 0) {
        this.scope.set('scalingGroup', options.model.at(0));
      }



      var t1 = new tab1({model:this.scope, bind: false});
      var t2 = new tab2({model:this.scope});
      var t3 = new tab3({model:this.scope});

      this._do_init( function(view) {
        setTimeout( function() {
          view.$el.find('#tabs-1').append(t1.render().el);
          view.$el.find('#tabs-2').append(t2.render().el);
          view.$el.find('#tabs-3').append(t3.render().el);
        }, 1000);
      });
    },

  });
});