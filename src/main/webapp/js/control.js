//home
$("#showForm").click(function (e) {
  e.preventDefault();
  $("#leftLinks").hide();
  $("#form").fadeIn();
  $("#backHome").fadeIn();
});

//form

$("#backHome").click(function (e) {
  e.preventDefault();
  $("#form").hide();
  $("#backHome").hide();
  $("#leftLinks").fadeIn();
});

$("#submitButton").click(function (e) {
  var apiUri = "/create",
    isInvalidForm = checkForm();
  e.preventDefault();
  if (!isInvalidForm) {
    $.ajax({
      type: "POST",
      contentType: "application/json",
      dataType: "json",
      url: apiUri,
      data: JSON.stringify({
        'address': $('#address').val() || 'invalidAddress',
        'amount': $('#amount').val() * 100000 || 0,
        'memo': $('#memo').val() || 'Click or tap "Send" to broadcast payment',
        'ackMemo': $('#ackMemo').val() || 'Thanks! Your payment is being processed by the Bitcoin network. Create your own Bitcoin link at DoubleSha.com.'
      }),
      success: function (data) {
        // This outputs the result of the ajax request
        console.log(data);
        $("#form").hide();
        $("#backHome").hide();
        $("#responseURI").html(data.uri);
        $("#response").fadeIn();
      }
    });
  } else {
    //display errors
    if (isInvalidForm["Bitcoin Address"]) {
      alertify.error(isInvalidForm["Bitcoin Address"][0]);
    }
    if (isInvalidForm.Amount) {
      alertify.error(isInvalidForm.Amount[0]);
    }
  }
});

$("#goBack").click(function () {
  //hide response and show form
  $("#response").hide();
  $("#form").fadeIn();
  $("#backHome").fadeIn();
  //clear fields
  $("#address").val("");
  $("#amount").val("");
  $("#memo").val("");
  $("#ackMemo").val("");
});

$("#advanceButton").click(function () {
  $("#advanceMenu").slideToggle();
});


//fancybox
$(".fancyLinks").fancybox({
  maxWidth: 800,
  maxHeight: 600,
  fitToView: false,
  width: '70%',
  height: '70%',
  autoSize: false,
  closeClick: false,
  openEffect: 'none',
  closeEffect: 'none'
});

//faq
$("#getStarted li.faq").click(function () {
  $(this).find(".answer").slideToggle();
});